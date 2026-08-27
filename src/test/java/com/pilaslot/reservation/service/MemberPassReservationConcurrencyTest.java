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
import com.pilaslot.pass.domain.MemberPassHistoryActorType;
import com.pilaslot.pass.domain.MemberPassHistoryType;
import com.pilaslot.pass.domain.PassProduct;
import com.pilaslot.pass.repository.MemberPassHistoryRepository;
import com.pilaslot.pass.repository.MemberPassRepository;
import com.pilaslot.pass.repository.PassProductRepository;
import com.pilaslot.reservation.repository.ReservationRepository;
import com.pilaslot.support.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
@Import({
        PostgreSqlTestContainerConfiguration.class,
        MemberPassReservationConcurrencyTest.FixedClockConfiguration.class
})
class MemberPassReservationConcurrencyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 19, 13, 0, 1);

    @Autowired private ReservationService reservationService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private InstructorRepository instructorRepository;
    @Autowired private ClassSessionRepository classSessionRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PassProductRepository passProductRepository;
    @Autowired private MemberPassRepository memberPassRepository;
    @Autowired private MemberPassHistoryRepository historyRepository;

    @Test
    void sameMemberCannotSpendOneRemainingPassOnTwoDifferentClassesConcurrently() throws Exception {
        Member member = memberRepository.save(new Member(
                "same-member-" + UUID.randomUUID(), "password", "동시 회원", "010-0000-0000"
        ));
        PassProduct product = passProductRepository.save(new PassProduct(
                "ONE_" + UUID.randomUUID(), "1회 테스트권", 50000, 1, 30
        ));
        LocalDate classDate = LocalDate.of(2026, 8, 21);
        MemberPass memberPass = memberPassRepository.save(MemberPass.issue(
                member, product, 50000, 1, classDate.minusDays(1), classDate.plusDays(1)
        ));
        historyRepository.save(MemberPassHistory.issued(
                memberPass, MemberPassHistoryActorType.SYSTEM, null
        ));

        Instructor instructor = instructorRepository.save(new Instructor("동시성 강사", null));
        List<Long> classSessionIds = List.of(
                saveClassSession(instructor, classDate.atTime(15, 0)).getId(),
                saveClassSession(instructor, classDate.atTime(17, 0)).getId()
        );

        AtomicInteger successes = new AtomicInteger();
        ConcurrentHashMap<ErrorCode, AtomicInteger> failures = new ConcurrentHashMap<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        var executor = Executors.newFixedThreadPool(2);

        for (Long classSessionId : classSessionIds) {
            executor.submit(() -> {
                try {
                    start.await();
                    reservationService.reserve(member.getId(), classSessionId);
                    successes.incrementAndGet();
                } catch (BusinessException exception) {
                    failures.computeIfAbsent(exception.getErrorCode(), ignored -> new AtomicInteger())
                            .incrementAndGet();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        MemberPass persisted = memberPassRepository.findById(memberPass.getId()).orElseThrow();
        List<MemberPassHistory> histories = historyRepository
                .findAllByMemberPassIdOrderByIdAsc(memberPass.getId());

        assertThat(successes.get()).isEqualTo(1);
        assertThat(failures).containsOnlyKeys(ErrorCode.NO_USABLE_MEMBER_PASS);
        assertThat(failures.get(ErrorCode.NO_USABLE_MEMBER_PASS).get()).isEqualTo(1);
        assertThat(reservationRepository.findAll().stream()
                .filter(reservation -> reservation.getMember().getId().equals(member.getId())))
                .hasSize(1);
        assertThat(persisted.getRemainingCount()).isZero();
        assertThat(histories.stream()
                .filter(history -> history.getType() == MemberPassHistoryType.RESERVATION_DEBIT))
                .hasSize(1);
        assertThat(histories.stream().mapToInt(MemberPassHistory::getCountDelta).sum())
                .isEqualTo(persisted.getRemainingCount());
    }

    private ClassSession saveClassSession(Instructor instructor, LocalDateTime startAt) {
        return classSessionRepository.save(new ClassSession(
                instructor,
                ClassType.REFORMER,
                startAt,
                50,
                NOW.minusDays(7),
                4,
                ClassSessionStatus.SCHEDULED
        ));
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
