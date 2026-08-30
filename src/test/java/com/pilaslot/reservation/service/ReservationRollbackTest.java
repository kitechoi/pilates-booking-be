package com.pilaslot.reservation.service;

import com.pilaslot.classsession.domain.ClassSession;
import com.pilaslot.classsession.domain.ClassSessionStatus;
import com.pilaslot.classsession.domain.ClassType;
import com.pilaslot.classsession.repository.ClassSessionRepository;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@ActiveProfiles("test")
@SpringBootTest
@Import({
        PostgreSqlTestContainerConfiguration.class,
        ReservationRollbackTest.FixedClockConfiguration.class
})
class ReservationRollbackTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 19, 13, 0, 1);
    private static final LocalDateTime CLASS_START_AT = LocalDateTime.of(2026, 8, 21, 15, 0);
    private static final LocalDate CLASS_DATE = CLASS_START_AT.toLocalDate();
    private static final int INITIAL_PASS_COUNT = 30;
    private static final String FORCED_FAILURE_MESSAGE = "forced-failure-for-rollback-test";

    @Autowired private ReservationService reservationService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private InstructorRepository instructorRepository;
    @Autowired private ClassSessionRepository classSessionRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PassProductRepository passProductRepository;
    @Autowired private MemberPassRepository memberPassRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private MemberPassHistoryRepository memberPassHistoryRepository;

    @Test
    void reserveRollsBackDebitAndReservationWhenHistorySaveFailsMidTransaction() {
        Member member = memberRepository.save(new Member(
                "rollback-reserve-" + UUID.randomUUID(), "encoded-password", "롤백 테스트 회원", "010-0000-0000"
        ));
        Instructor instructor = instructorRepository.save(new Instructor("롤백 테스트 강사", null));
        ClassSession classSession = classSessionRepository.save(new ClassSession(
                instructor, ClassType.REFORMER, CLASS_START_AT, 50, NOW.minusDays(7),
                10, ClassSessionStatus.SCHEDULED
        ));
        MemberPass memberPass = PersistentPassFixtures.issueAtomically(
                member, CLASS_DATE, passProductRepository, memberPassRepository,
                memberPassHistoryRepository, transactionManager
        );
        stubHistorySaveToFailOnType(MemberPassHistoryType.RESERVATION_DEBIT);

        assertThatThrownBy(() -> reservationService.reserve(member.getId(), classSession.getId()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage(FORCED_FAILURE_MESSAGE);

        List<Reservation> reservations = reservationRepository.findAll().stream()
                .filter(reservation -> reservation.getMember().getId().equals(member.getId()))
                .toList();
        MemberPass persistedPass = memberPassRepository.findById(memberPass.getId()).orElseThrow();
        ClassSession persistedSession = classSessionRepository.findById(classSession.getId()).orElseThrow();

        assertThat(reservations)
                .as("history 저장 실패로 전체 트랜잭션이 롤백되어 예약 행이 남지 않아야 한다")
                .isEmpty();
        assertThat(persistedPass.getRemainingCount())
                .as("debit()으로 이미 감소했던 in-memory 값도 롤백되어 원래대로 돌아와야 한다")
                .isEqualTo(INITIAL_PASS_COUNT);
        assertThat(persistedSession.getReservedCount())
                .as("reserved_count 증가는 history 저장 실패 지점보다 뒤에 있어 애초에 실행되지 않는다")
                .isZero();
    }

    @Test
    void cancelRollsBackRefundAndReservationStatusWhenHistorySaveFailsMidTransaction() {
        Member member = memberRepository.save(new Member(
                "rollback-cancel-" + UUID.randomUUID(), "encoded-password", "롤백 테스트 회원", "010-0000-0000"
        ));
        Instructor instructor = instructorRepository.save(new Instructor("롤백 테스트 강사", null));
        ClassSession classSession = classSessionRepository.save(new ClassSession(
                instructor, ClassType.REFORMER, CLASS_START_AT, 50, NOW.minusDays(7),
                10, ClassSessionStatus.SCHEDULED
        ));
        MemberPass memberPass = PersistentPassFixtures.issueAtomically(
                member, CLASS_DATE, passProductRepository, memberPassRepository,
                memberPassHistoryRepository, transactionManager
        );
        Reservation existingReservation = new TransactionTemplate(transactionManager).execute(status -> {
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
        stubHistorySaveToFailOnType(MemberPassHistoryType.CANCELLATION_REFUND);

        assertThatThrownBy(() -> reservationService.cancel(member.getId(), existingReservation.getId()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage(FORCED_FAILURE_MESSAGE);

        Reservation persistedReservation = reservationRepository.findById(existingReservation.getId()).orElseThrow();
        MemberPass persistedPass = memberPassRepository.findById(memberPass.getId()).orElseThrow();
        ClassSession persistedSession = classSessionRepository.findById(classSession.getId()).orElseThrow();

        assertThat(persistedReservation.getStatus())
                .as("history 저장 실패로 롤백되어 예약 상태는 RESERVED로 남아야 한다")
                .isEqualTo(ReservationStatus.RESERVED);
        assertThat(persistedReservation.getCancelledAt())
                .isNull();
        assertThat(persistedPass.getRemainingCount())
                .as("refund()으로 이미 증가했던 in-memory 값도 롤백되어 원래대로 돌아와야 한다")
                .isEqualTo(INITIAL_PASS_COUNT - 1);
        assertThat(persistedSession.getReservedCount())
                .as("reserved_count 감소는 history 저장 실패 지점보다 뒤에 있어 애초에 실행되지 않는다")
                .isEqualTo(1);
    }

    private void stubHistorySaveToFailOnType(MemberPassHistoryType failingType) {
        doAnswer(invocation -> {
            MemberPassHistory history = invocation.getArgument(0);
            assertThat(history.getType()).isEqualTo(failingType);
            throw new RuntimeException(FORCED_FAILURE_MESSAGE);
        }).when(memberPassHistoryRepository).save(any(MemberPassHistory.class));
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
