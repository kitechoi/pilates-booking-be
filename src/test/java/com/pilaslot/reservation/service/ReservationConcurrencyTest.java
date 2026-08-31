package com.pilaslot.reservation.service;

import com.pilaslot.classsession.domain.ClassSession;
import com.pilaslot.classsession.domain.ClassSessionStatus;
import com.pilaslot.classsession.domain.ClassType;
import com.pilaslot.classsession.repository.ClassSessionRepository;
import com.pilaslot.global.exception.BusinessException;
import com.pilaslot.global.exception.ErrorCode;
import com.pilaslot.instructor.domain.Instructor;
import com.pilaslot.instructor.repository.InstructorRepository;
import com.pilaslot.member.domain.Member;
import com.pilaslot.member.repository.MemberRepository;
import com.pilaslot.pass.domain.MemberPass;
import com.pilaslot.pass.domain.MemberPassHistory;
import com.pilaslot.pass.repository.MemberPassHistoryRepository;
import com.pilaslot.pass.repository.MemberPassRepository;
import com.pilaslot.pass.repository.PassProductRepository;
import com.pilaslot.reservation.domain.Reservation;
import com.pilaslot.reservation.domain.ReservationStatus;
import com.pilaslot.reservation.repository.ReservationRepository;
import com.pilaslot.support.PostgreSqlTestContainerConfiguration;
import com.pilaslot.support.PersistentPassFixtures;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * 문제 1 재현/회귀 테스트: 정원 4자리 수업에 이미 3명이 예약된 상태(마지막 1자리)에서
 * 여러 회원이 동시에 그 자리를 두고 경쟁할 때, 정원 초과와 lost update가 발생하는지 확인한다.
 *
 * 클래스 레벨에 @Transactional을 두지 않는다 — 각 스레드가 실제로 별개의 DB 트랜잭션/커넥션을
 * 열어야 race condition이 재현된다. (기존 ReservationServiceIntegrationTest는 클래스 전체가
 * 하나의 트랜잭션으로 묶여 롤백되므로 이 목적에는 쓸 수 없다.)
 */
@ActiveProfiles("test")
@SpringBootTest
@Import({
        PostgreSqlTestContainerConfiguration.class,
        ReservationConcurrencyTest.FixedClockConfiguration.class
})
class ReservationConcurrencyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 19, 13, 0, 1);
    private static final int CAPACITY = 4;
    private static final int ALREADY_RESERVED = 3;
    private static final int CONCURRENT_REQUESTS = 5;

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private PassProductRepository passProductRepository;

    @Autowired
    private MemberPassRepository memberPassRepository;

    @Autowired
    private MemberPassHistoryRepository memberPassHistoryRepository;

    @DynamicPropertySource
    static void increaseConnectionPool(DynamicPropertyRegistry registry) {
        // 동시 요청 수만큼 커넥션을 확보해, "락 대기"가 아니라 "커넥션 풀 대기"로
        // 결과가 왜곡되는 것을 방지한다.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> CONCURRENT_REQUESTS + 5);
    }

    @Test
    void concurrentReservationsForLastRemainingSeatAreLimitedToOne() throws InterruptedException {
        // 정원 4자리 수업에 이미 3명이 예약해 마지막 1자리만 남은 상태에서,
        // 서로 다른 회원 5명이 그 자리를 두고 동시에 예약을 시도한다.
        TrialResult result = runConcurrencyTrial(CAPACITY, ALREADY_RESERVED, CONCURRENT_REQUESTS);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result.completed())
                .as("모든 스레드가 타임아웃 없이 종료해야 한다")
                .isTrue();
        softly.assertThat(result.unexpectedFailures())
                .as("예상치 못한 예외가 없어야 한다")
                .isEmpty();
        softly.assertThat(result.successCount())
                .as("마지막 1자리를 두고 경쟁하므로 성공은 정확히 1건이어야 한다")
                .isEqualTo(1);
        softly.assertThat(result.failureBreakdown())
                .as("실패 원인은 CLASS_SESSION_FULL 하나뿐이어야 한다")
                .containsOnlyKeys(ErrorCode.CLASS_SESSION_FULL.name());
        softly.assertThat(result.failureBreakdown().get(ErrorCode.CLASS_SESSION_FULL.name()).get())
                .as("나머지 요청은 모두 CLASS_SESSION_FULL로 거절되어야 한다")
                .isEqualTo(CONCURRENT_REQUESTS - 1);
        softly.assertThat(result.actualReservedRows())
                .as("실제 RESERVED 예약 행 개수는 정원과 정확히 같아야 한다 (기존 3 + 신규 1)")
                .isEqualTo((long) CAPACITY);
        softly.assertThat(result.persistedReservedCount())
                .as("class_session.reserved_count는 항상 실제 RESERVED 예약 건수와 같아야 한다")
                .isEqualTo((int) result.actualReservedRows());
        assertMemberPassBalancesReconcile(result.racingMemberIds());
        softly.assertAll();
    }

    private void assertMemberPassBalancesReconcile(List<Long> memberIds) {
        SoftAssertions softly = new SoftAssertions();
        for (Long memberId : memberIds) {
            for (MemberPass memberPass : memberPassRepository.findAllByMemberIdOrderByExpiresOnAscIdAsc(memberId)) {
                int historySum = memberPassHistoryRepository.findAllByMemberPassIdOrderByIdAsc(memberPass.getId())
                        .stream()
                        .mapToInt(MemberPassHistory::getCountDelta)
                        .sum();
                softly.assertThat(memberPass.getRemainingCount())
                        .as("memberPassId=%d의 remainingCount는 history countDelta 합과 같아야 한다", memberPass.getId())
                        .isEqualTo(historySum);
            }
        }
        softly.assertAll();
    }

    private TrialResult runConcurrencyTrial(
            int capacity,
            int alreadyReserved,
            int concurrentRequests
    ) throws InterruptedException {
        Instructor instructor = instructorRepository.save(new Instructor("동시성 테스트 강사", null));
        ClassSession classSession = classSessionRepository.save(new ClassSession(
                instructor,
                ClassType.REFORMER,
                LocalDateTime.of(2026, 8, 21, 15, 0),
                50,
                LocalDateTime.of(2026, 8, 12, 9, 0),
                capacity,
                ClassSessionStatus.SCHEDULED
        ));

        // 이미 마감 직전(alreadyReserved명)까지 예약된 상태를 실제 예약 행으로 구성한다.
        for (int i = 0; i < alreadyReserved; i++) {
            Member existingMember = memberRepository.save(new Member(
                    "existing-" + i,
                    "encoded-password",
                    "기존 회원 " + i,
                    "010-0000-0000"
            ));
            saveDebitedReservation(existingMember, classSession);
            classSession.increaseReservedCount();
        }
        classSession = classSessionRepository.save(classSession);
        Long classSessionId = classSession.getId();
        var classDate = classSession.getStartAt().toLocalDate();

        List<Long> racingMemberIds = IntStream.range(0, concurrentRequests)
                .mapToObj(i -> memberRepository.save(new Member(
                        "racing-" + i,
                        "encoded-password",
                        "경쟁 회원 " + i,
                        "010-0000-0000"
                )))
                .peek(member -> PersistentPassFixtures.issueAtomically(
                        member,
                        classDate,
                        passProductRepository,
                        memberPassRepository,
                        memberPassHistoryRepository,
                        transactionManager
                ))
                .map(Member::getId)
                .toList();

        AtomicInteger successCount = new AtomicInteger();
        ConcurrentHashMap<String, AtomicInteger> failureBreakdown = new ConcurrentHashMap<>();
        CopyOnWriteArrayList<Throwable> unexpectedFailures = new CopyOnWriteArrayList<>();

        ExecutorService executorService = Executors.newFixedThreadPool(concurrentRequests);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(concurrentRequests);

        for (Long memberId : racingMemberIds) {
            executorService.submit(() -> {
                try {
                    startGate.await();
                    reservationService.reserve(memberId, classSessionId);
                    successCount.incrementAndGet();
                } catch (BusinessException exception) {
                    failureBreakdown.computeIfAbsent(
                            exception.getErrorCode().name(),
                            key -> new AtomicInteger()
                    ).incrementAndGet();
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

        long actualReservedRows = reservationRepository.findAll().stream()
                .filter(reservation -> reservation.getClassSession().getId().equals(classSessionId))
                .filter(reservation -> reservation.getStatus() == ReservationStatus.RESERVED)
                .count();
        int persistedReservedCount = classSessionRepository.findById(classSessionId)
                .orElseThrow()
                .getReservedCount();

        System.out.printf(
                "[concurrency result] capacity=%d, alreadyReserved=%d, requests=%d, success=%d, "
                        + "actualReservedRows=%d, persistedReservedCount=%d, failures=%s%n",
                capacity, alreadyReserved, concurrentRequests, successCount.get(),
                actualReservedRows, persistedReservedCount, failureBreakdown
        );
        reservationRepository.findAll().stream()
                .filter(reservation -> reservation.getClassSession().getId().equals(classSessionId))
                .sorted(Comparator.comparing(Reservation::getId))
                .forEach(reservation -> System.out.printf(
                        "[reservation row] id=%d, memberId=%d, status=%s%n",
                        reservation.getId(),
                        reservation.getMember().getId(),
                        reservation.getStatus()
                ));

        return new TrialResult(
                completed,
                successCount.get(),
                actualReservedRows,
                persistedReservedCount,
                failureBreakdown,
                unexpectedFailures,
                racingMemberIds
        );
    }

    private Reservation saveDebitedReservation(Member member, ClassSession classSession) {
        MemberPass memberPass = PersistentPassFixtures.issueAtomically(
                member,
                classSession.getStartAt().toLocalDate(),
                passProductRepository,
                memberPassRepository,
                memberPassHistoryRepository,
                transactionManager
        );
        return new TransactionTemplate(transactionManager).execute(status -> {
            MemberPass persistedPass = memberPassRepository.findById(memberPass.getId()).orElseThrow();
            persistedPass.debit();
            Reservation reservation = reservationRepository.save(Reservation.reserve(
                    member,
                    classSession,
                    persistedPass,
                    NOW.minusDays(1)
            ));
            memberPassHistoryRepository.save(MemberPassHistory.reservationDebit(persistedPass, reservation));
            return reservation;
        });
    }

    private record TrialResult(
            boolean completed,
            int successCount,
            long actualReservedRows,
            int persistedReservedCount,
            Map<String, AtomicInteger> failureBreakdown,
            List<Throwable> unexpectedFailures,
            List<Long> racingMemberIds
    ) {
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
