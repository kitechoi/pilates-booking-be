package com.pilaslot.reservation.service;

import com.pilaslot.classsession.domain.ClassSession;
import com.pilaslot.classsession.domain.ClassSessionStatus;
import com.pilaslot.classsession.domain.ClassType;
import com.pilaslot.classsession.repository.ClassSessionRepository;
import com.pilaslot.instructor.domain.Instructor;
import com.pilaslot.instructor.repository.InstructorRepository;
import com.pilaslot.member.domain.Member;
import com.pilaslot.member.repository.MemberRepository;
import com.pilaslot.reservation.domain.Reservation;
import com.pilaslot.reservation.domain.ReservationStatus;
import com.pilaslot.reservation.repository.ReservationRepository;
import com.pilaslot.support.PostgreSqlTestContainerConfiguration;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 문제 2 회귀 테스트: cancel()에 비관적 락(findByIdForUpdate)을 적용한 뒤,
 * A) 동일 예약 동시 취소 시 중복 취소가 정상적으로 막히는지,
 * B) 서로 다른 예약 동시 취소 시 reserved_count가 lost update 없이 정확히 반영되는지 검증한다.
 *
 * ReservationConcurrencyTest(문제 1)와 동일하게 클래스 레벨 @Transactional을 두지 않는다 —
 * 각 스레드가 실제 별개의 DB 트랜잭션을 열어야 race condition 여부를 검증할 수 있다.
 */
@ActiveProfiles("test")
@SpringBootTest
@Import({
        PostgreSqlTestContainerConfiguration.class,
        CancelConcurrencyTest.FixedClockConfiguration.class
})
class CancelConcurrencyTest {

    private static final LocalDateTime CLASS_START_AT = LocalDateTime.of(2026, 8, 21, 15, 0);
    private static final LocalDateTime NOW = CLASS_START_AT.minusHours(9); // 취소 마감(8시간 전)보다 여유
    private static final int CAPACITY = 5;
    private static final int ALREADY_RESERVED = 3; // target 1건 + padding 2건
    private static final int CONCURRENT_CANCEL_REQUESTS = 5;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private InstructorRepository instructorRepository;

    @Autowired
    private ClassSessionRepository classSessionRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @DynamicPropertySource
    static void increaseConnectionPool(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> CONCURRENT_CANCEL_REQUESTS + 5);
    }

    @RepeatedTest(10)
    void concurrentCancelOfSameReservationIsSerializedByLock(RepetitionInfo repetitionInfo) throws InterruptedException {
        String memberPrefix = "member-" + repetitionInfo.getCurrentRepetition() + "-";
        Instructor instructor = instructorRepository.save(new Instructor("동시성 테스트 강사", null));
        ClassSession classSession = classSessionRepository.save(new ClassSession(
                instructor,
                ClassType.REFORMER,
                CLASS_START_AT,
                50,
                CLASS_START_AT.minusDays(9),
                CAPACITY,
                ClassSessionStatus.SCHEDULED
        ));

        // padding 예약 2건 + 취소 대상 예약 1건 = reservedCount 3으로 세팅
        Long targetReservationId = null;
        Long targetMemberId = null;
        for (int i = 0; i < ALREADY_RESERVED; i++) {
            Member member = memberRepository.save(new Member(
                    memberPrefix + i,
                    "encoded-password",
                    "회원 " + i,
                    "010-0000-0000"
            ));
            Reservation reservation = reservationRepository.save(
                    Reservation.reserve(member, classSession, NOW.minusDays(1))
            );
            classSession.increaseReservedCount();
            if (i == 0) {
                targetReservationId = reservation.getId();
                targetMemberId = member.getId();
            }
        }
        classSession = classSessionRepository.save(classSession);
        Long classSessionId = classSession.getId();
        int reservedCountBeforeCancel = classSession.getReservedCount();

        AtomicInteger successCount = new AtomicInteger();
        ConcurrentHashMap<String, AtomicInteger> failureBreakdown = new ConcurrentHashMap<>();
        CopyOnWriteArrayList<Throwable> unexpectedFailures = new CopyOnWriteArrayList<>();

        ExecutorService executorService = Executors.newFixedThreadPool(CONCURRENT_CANCEL_REQUESTS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(CONCURRENT_CANCEL_REQUESTS);

        Long finalTargetReservationId = targetReservationId;
        Long finalTargetMemberId = targetMemberId;
        for (int i = 0; i < CONCURRENT_CANCEL_REQUESTS; i++) {
            executorService.submit(() -> {
                try {
                    startGate.await();
                    reservationService.cancel(finalTargetMemberId, finalTargetReservationId);
                    successCount.incrementAndGet();
                } catch (Exception exception) {
                    String reason = exception.getClass().getSimpleName() + ":" + rootCauseMessage(exception);
                    failureBreakdown.computeIfAbsent(reason, key -> new AtomicInteger()).incrementAndGet();
                } catch (Throwable throwable) {
                    unexpectedFailures.add(throwable);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = doneGate.await(30, TimeUnit.SECONDS);
        executorService.shutdown();

        Reservation persistedReservation = reservationRepository.findById(finalTargetReservationId).orElseThrow();
        int persistedReservedCount = classSessionRepository.findById(classSessionId).orElseThrow().getReservedCount();
        int expectedReservedCountAfterOneRealCancel = reservedCountBeforeCancel - 1;

        System.out.printf(
                "[cancel-concurrency] completed=%s, requests=%d, success=%d, failures=%s, unexpected=%s, "
                        + "reservationStatus=%s, reservedCountBefore=%d, reservedCountAfter=%d, expectedAfter=%d%n",
                completed, CONCURRENT_CANCEL_REQUESTS, successCount.get(), failureBreakdown, unexpectedFailures,
                persistedReservation.getStatus(), reservedCountBeforeCancel, persistedReservedCount,
                expectedReservedCountAfterOneRealCancel
        );

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(completed)
                .as("모든 스레드가 타임아웃 없이 종료해야 한다")
                .isTrue();
        softly.assertThat(unexpectedFailures)
                .as("예상치 못한 예외가 없어야 한다")
                .isEmpty();
        softly.assertThat(successCount.get())
                .as("같은 예약에 대한 동시 취소는 정확히 1건만 성공해야 한다")
                .isEqualTo(1);
        softly.assertThat(failureBreakdown.getOrDefault("BusinessException:이미 취소된 예약입니다.", new AtomicInteger()).get())
                .as("나머지 취소 요청은 RESERVATION_ALREADY_CANCELLED로 막혀야 한다")
                .isEqualTo(CONCURRENT_CANCEL_REQUESTS - 1);
        softly.assertThat(persistedReservation.getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        softly.assertThat(persistedReservedCount)
                .as("reserved_count는 실제 취소 1건만큼만 감소해야 한다")
                .isEqualTo(expectedReservedCountAfterOneRealCancel);
        softly.assertAll();
    }

    @RepeatedTest(10)
    void concurrentCancelOfDifferentReservationsPreservesReservedCountUnderLock(RepetitionInfo repetitionInfo)
            throws InterruptedException {
        String memberPrefix = "member2-" + repetitionInfo.getCurrentRepetition() + "-";
        Instructor instructor = instructorRepository.save(new Instructor("동시성 테스트 강사", null));
        ClassSession classSession = classSessionRepository.save(new ClassSession(
                instructor,
                ClassType.REFORMER,
                CLASS_START_AT,
                50,
                CLASS_START_AT.minusDays(9),
                CAPACITY,
                ClassSessionStatus.SCHEDULED
        ));

        // 서로 다른 회원 5명이 각각 별도 예약을 가진 상태(reservedCount=5)에서,
        // 그 중 다른 사람 소유의 5개 예약을 동시에 취소한다 (문제 1과 동일 계열 lost update).
        java.util.List<Long> reservationIds = new java.util.ArrayList<>();
        java.util.List<Long> memberIds = new java.util.ArrayList<>();
        for (int i = 0; i < CONCURRENT_CANCEL_REQUESTS; i++) {
            Member member = memberRepository.save(new Member(
                    memberPrefix + i,
                    "encoded-password",
                    "회원 " + i,
                    "010-0000-0000"
            ));
            Reservation reservation = reservationRepository.save(
                    Reservation.reserve(member, classSession, NOW.minusDays(1))
            );
            classSession.increaseReservedCount();
            reservationIds.add(reservation.getId());
            memberIds.add(member.getId());
        }
        classSession = classSessionRepository.save(classSession);
        Long classSessionId = classSession.getId();
        int reservedCountBeforeCancel = classSession.getReservedCount();

        AtomicInteger successCount = new AtomicInteger();
        ConcurrentHashMap<String, AtomicInteger> failureBreakdown = new ConcurrentHashMap<>();
        CopyOnWriteArrayList<Throwable> unexpectedFailures = new CopyOnWriteArrayList<>();

        ExecutorService executorService = Executors.newFixedThreadPool(CONCURRENT_CANCEL_REQUESTS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(CONCURRENT_CANCEL_REQUESTS);

        for (int i = 0; i < CONCURRENT_CANCEL_REQUESTS; i++) {
            Long memberId = memberIds.get(i);
            Long reservationId = reservationIds.get(i);
            executorService.submit(() -> {
                try {
                    startGate.await();
                    reservationService.cancel(memberId, reservationId);
                    successCount.incrementAndGet();
                } catch (Exception exception) {
                    String reason = exception.getClass().getSimpleName() + ":" + rootCauseMessage(exception);
                    failureBreakdown.computeIfAbsent(reason, key -> new AtomicInteger()).incrementAndGet();
                } catch (Throwable throwable) {
                    unexpectedFailures.add(throwable);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = doneGate.await(30, TimeUnit.SECONDS);
        executorService.shutdown();

        long actualCancelledRows = reservationRepository.findAll().stream()
                .filter(reservation -> reservationIds.contains(reservation.getId()))
                .filter(reservation -> reservation.getStatus() == ReservationStatus.CANCELLED)
                .count();
        int persistedReservedCount = classSessionRepository.findById(classSessionId).orElseThrow().getReservedCount();
        int expectedReservedCount = (int) (reservedCountBeforeCancel - actualCancelledRows);

        System.out.printf(
                "[cancel-concurrency-diff-rows] completed=%s, requests=%d, success=%d, failures=%s, unexpected=%s, "
                        + "actualCancelledRows=%d, reservedCountBefore=%d, reservedCountAfter=%d, expectedAfter=%d%n",
                completed, CONCURRENT_CANCEL_REQUESTS, successCount.get(), failureBreakdown, unexpectedFailures,
                actualCancelledRows, reservedCountBeforeCancel, persistedReservedCount, expectedReservedCount
        );

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(completed)
                .as("모든 스레드가 타임아웃 없이 종료해야 한다")
                .isTrue();
        softly.assertThat(unexpectedFailures)
                .as("예상치 못한 예외가 없어야 한다")
                .isEmpty();
        softly.assertThat(failureBreakdown)
                .as("서로 다른 예약이므로 전부 성공해야 한다")
                .isEmpty();
        softly.assertThat(actualCancelledRows)
                .isEqualTo(CONCURRENT_CANCEL_REQUESTS);
        softly.assertThat(persistedReservedCount)
                .as("reserved_count는 lost update 없이 실제 취소 건수만큼 정확히 감소해야 한다")
                .isEqualTo(expectedReservedCount);
        softly.assertAll();
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            ZoneId zoneId = ZoneId.of("Asia/Seoul");
            Instant instant = NOW.atZone(zoneId).toInstant();
            return Clock.fixed(instant, zoneId);
        }
    }
}
