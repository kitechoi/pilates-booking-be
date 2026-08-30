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
import com.pilaslot.pass.domain.MemberPassHistoryType;
import com.pilaslot.pass.repository.MemberPassHistoryRepository;
import com.pilaslot.pass.repository.MemberPassRepository;
import com.pilaslot.pass.repository.PassProductRepository;
import com.pilaslot.reservation.domain.Reservation;
import com.pilaslot.reservation.domain.ReservationStatus;
import com.pilaslot.reservation.repository.ReservationRepository;
import com.pilaslot.support.PersistentPassFixtures;
import com.pilaslot.support.PostgreSqlTestContainerConfiguration;
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
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
@Import({
        PostgreSqlTestContainerConfiguration.class,
        WeeklyLimitConcurrencyTest.FixedClockConfiguration.class
})
class WeeklyLimitConcurrencyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 19, 13, 0, 1);
    private static final LocalDate CLASS_DATE = LocalDate.of(2026, 8, 21);
    private static final int EXISTING_RESERVATIONS = 13;
    private static final long WEEKLY_RESERVATION_LIMIT = 14;
    private static final int INITIAL_PASS_COUNT = 30;
    private static final int SESSION_CAPACITY = 10;
    private static final int RACING_REQUESTS = 2;

    @Autowired private ReservationService reservationService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private InstructorRepository instructorRepository;
    @Autowired private ClassSessionRepository classSessionRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PassProductRepository passProductRepository;
    @Autowired private MemberPassRepository memberPassRepository;
    @Autowired private MemberPassHistoryRepository memberPassHistoryRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void increaseConnectionPool(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> RACING_REQUESTS + 5);
    }

    @Test
    void concurrentReservationsNearWeeklyLimitAreCappedAtFourteen() throws InterruptedException {
        Member member = memberRepository.save(new Member(
                "weekly-limit-" + UUID.randomUUID(), "encoded-password", "주간 제한 회원", "010-0000-0000"
        ));
        Instructor instructor = instructorRepository.save(new Instructor("주간 제한 테스트 강사", null));
        MemberPass memberPass = PersistentPassFixtures.issueAtomically(
                member, CLASS_DATE, passProductRepository, memberPassRepository,
                memberPassHistoryRepository, transactionManager
        );

        List<ClassSession> existingSessions = IntStream.range(0, EXISTING_RESERVATIONS)
                .mapToObj(i -> saveClassSession(instructor, CLASS_DATE.atTime(9, 0).plusMinutes(i * 30L)))
                .toList();
        for (ClassSession session : existingSessions) {
            saveDebitedReservation(member, memberPass, session);
        }

        ClassSession classSessionA = saveClassSession(instructor, CLASS_DATE.atTime(20, 0));
        ClassSession classSessionB = saveClassSession(instructor, CLASS_DATE.atTime(21, 0));
        List<Long> racingClassSessionIds = List.of(classSessionA.getId(), classSessionB.getId());

        AtomicInteger successCount = new AtomicInteger();
        ConcurrentHashMap<ErrorCode, AtomicInteger> failureBreakdown = new ConcurrentHashMap<>();
        CopyOnWriteArrayList<Throwable> unexpectedFailures = new CopyOnWriteArrayList<>();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(racingClassSessionIds.size());
        ExecutorService executor = Executors.newFixedThreadPool(racingClassSessionIds.size());

        for (Long classSessionId : racingClassSessionIds) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    reservationService.reserve(member.getId(), classSessionId);
                    successCount.incrementAndGet();
                } catch (BusinessException exception) {
                    failureBreakdown.computeIfAbsent(exception.getErrorCode(), key -> new AtomicInteger())
                            .incrementAndGet();
                } catch (Throwable throwable) {
                    unexpectedFailures.add(throwable);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = doneGate.await(20, TimeUnit.SECONDS);
        executor.shutdown();

        LocalDateTime weekStart = CLASS_DATE.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
        LocalDateTime weekEnd = weekStart.plusWeeks(1);
        long reservedThisWeek = reservationRepository.countByMemberAndStatusInClassSessionWeek(
                member.getId(), ReservationStatus.RESERVED, weekStart, weekEnd
        );
        MemberPass persistedPass = memberPassRepository.findById(memberPass.getId()).orElseThrow();
        List<MemberPassHistory> histories = memberPassHistoryRepository
                .findAllByMemberPassIdOrderByIdAsc(memberPass.getId());
        long debitHistoryCount = histories.stream()
                .filter(history -> history.getType() == MemberPassHistoryType.RESERVATION_DEBIT)
                .count();
        int reservedCountSumForRacingSessions = classSessionRepository.findAllById(racingClassSessionIds).stream()
                .mapToInt(ClassSession::getReservedCount)
                .sum();

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(completed)
                .as("모든 스레드가 타임아웃 없이 종료해야 한다")
                .isTrue();
        softly.assertThat(unexpectedFailures)
                .as("예상치 못한 예외가 없어야 한다")
                .isEmpty();
        softly.assertThat(successCount.get())
                .as("주간 제한 14건 중 마지막 1자리를 두고 경쟁하므로 성공은 정확히 1건이어야 한다")
                .isEqualTo(1);
        softly.assertThat(failureBreakdown)
                .as("실패 원인은 WEEKLY_RESERVATION_LIMIT_EXCEEDED 하나뿐이어야 한다")
                .containsOnlyKeys(ErrorCode.WEEKLY_RESERVATION_LIMIT_EXCEEDED);
        softly.assertThat(failureBreakdown.getOrDefault(ErrorCode.WEEKLY_RESERVATION_LIMIT_EXCEEDED, new AtomicInteger()).get())
                .isEqualTo(1);
        softly.assertThat(reservedThisWeek)
                .as("Member 락 없이는 두 요청이 동시에 13건을 읽고 통과해 15건이 될 수 있다")
                .isEqualTo(WEEKLY_RESERVATION_LIMIT);
        softly.assertThat(reservedCountSumForRacingSessions)
                .as("두 경쟁 수업 중 정확히 하나만 reserved_count가 증가해야 한다")
                .isEqualTo(1);
        softly.assertThat(debitHistoryCount)
                .isEqualTo(WEEKLY_RESERVATION_LIMIT);
        softly.assertThat(persistedPass.getRemainingCount())
                .isEqualTo(INITIAL_PASS_COUNT - (int) WEEKLY_RESERVATION_LIMIT);
        softly.assertThat(histories.stream().mapToInt(MemberPassHistory::getCountDelta).sum())
                .as("history delta 합계는 remaining_count와 항상 일치해야 한다")
                .isEqualTo(persistedPass.getRemainingCount());
        softly.assertAll();
    }

    private ClassSession saveClassSession(Instructor instructor, LocalDateTime startAt) {
        return classSessionRepository.save(new ClassSession(
                instructor, ClassType.REFORMER, startAt, 50, NOW.minusDays(7),
                SESSION_CAPACITY, ClassSessionStatus.SCHEDULED
        ));
    }

    private void saveDebitedReservation(Member member, MemberPass memberPass, ClassSession classSession) {
        new TransactionTemplate(transactionManager).execute(status -> {
            MemberPass persistedPass = memberPassRepository.findById(memberPass.getId()).orElseThrow();
            persistedPass.debit();
            ClassSession persistedSession = classSessionRepository.findById(classSession.getId()).orElseThrow();
            Reservation reservation = reservationRepository.save(Reservation.reserve(
                    member, persistedSession, persistedPass, NOW.minusDays(1)
            ));
            memberPassHistoryRepository.save(MemberPassHistory.reservationDebit(persistedPass, reservation));
            persistedSession.increaseReservedCount();
            return reservation;
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            ZoneId zone = ZoneId.of("Asia/Seoul");
            Instant instant = NOW.atZone(zone).toInstant();
            return Clock.fixed(instant, zone);
        }
    }
}
