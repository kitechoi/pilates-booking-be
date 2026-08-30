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
import com.pilaslot.pass.domain.MemberPassHistoryType;
import com.pilaslot.pass.repository.MemberPassHistoryRepository;
import com.pilaslot.pass.repository.MemberPassRepository;
import com.pilaslot.pass.repository.PassProductRepository;
import com.pilaslot.reservation.domain.Reservation;
import com.pilaslot.reservation.domain.ReservationStatus;
import com.pilaslot.reservation.dto.response.ReservationCreateResponse;
import com.pilaslot.reservation.repository.ReservationRepository;
import com.pilaslot.support.PostgreSqlTestContainerConfiguration;
import com.pilaslot.support.PersistentPassFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
@ActiveProfiles("test")
@SpringBootTest
@Import({
        PostgreSqlTestContainerConfiguration.class,
        ReservationServiceIntegrationTest.FixedClockConfiguration.class
})
class ReservationServiceIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 19, 13, 0, 1);

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
    private PassProductRepository passProductRepository;

    @Autowired
    private MemberPassRepository memberPassRepository;

    @Autowired
    private MemberPassHistoryRepository memberPassHistoryRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void persistsReservationAndReservedCountInOneTransaction() {
        Fixture fixture = persistFixture("success");
        assertThat(fixture.classSession().getReservedCount()).isZero();

        ReservationCreateResponse response = reservationService.reserve(
                fixture.member().getId(),
                fixture.classSession().getId()
        );
        entityManager.flush();
        entityManager.clear();

        Reservation reservation = reservationRepository.findById(response.id()).orElseThrow();
        ClassSession classSession = classSessionRepository.findById(
                fixture.classSession().getId()
        ).orElseThrow();
        assertThat(reservation.getMember().getId()).isEqualTo(fixture.member().getId());
        assertThat(reservation.getClassSession().getId()).isEqualTo(classSession.getId());
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(reservation.getReservedAt()).isEqualTo(NOW);
        assertThat(reservation.getCancelledAt()).isNull();
        assertThat(reservation.getMemberPass().getId()).isEqualTo(fixture.memberPass().getId());
        assertThat(memberPassRepository.findById(fixture.memberPass().getId()).orElseThrow().getRemainingCount())
                .isEqualTo(29);
        assertThat(memberPassHistoryRepository.findAll())
                .extracting(history -> history.getType())
                .contains(MemberPassHistoryType.RESERVATION_DEBIT);
        assertThat(classSession.getReservedCount()).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateBeforePartialUniqueIndexIsViolated() {
        Fixture fixture = persistFixture("duplicate");
        reservationService.reserve(fixture.member().getId(), fixture.classSession().getId());
        entityManager.flush();

        assertThatThrownBy(() -> reservationService.reserve(
                fixture.member().getId(),
                fixture.classSession().getId()
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DUPLICATE_RESERVATION);
    }

    @Test
    void persistsCancellationAndReservedCountInOneTransaction() {
        Fixture fixture = persistFixture("cancel");
        ReservationCreateResponse response = reservationService.reserve(
                fixture.member().getId(),
                fixture.classSession().getId()
        );
        entityManager.flush();
        LocalDateTime reservedAt = response.reservedAt();

        reservationService.cancel(fixture.member().getId(), response.id());
        entityManager.flush();
        entityManager.clear();

        Reservation reservation = reservationRepository.findById(response.id()).orElseThrow();
        ClassSession classSession = classSessionRepository.findById(
                fixture.classSession().getId()
        ).orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelledAt()).isEqualTo(NOW);
        assertThat(reservation.getReservedAt()).isEqualTo(reservedAt);
        assertThat(reservation.getCancellationSource()).isEqualTo(com.pilaslot.reservation.domain.CancellationSource.MEMBER);
        assertThat(memberPassRepository.findById(fixture.memberPass().getId()).orElseThrow().getRemainingCount())
                .isEqualTo(30);
        assertThat(memberPassHistoryRepository.findAll())
                .extracting(history -> history.getType())
                .contains(MemberPassHistoryType.CANCELLATION_REFUND);
        assertThat(classSession.getReservedCount()).isZero();
    }

    @Test
    void createsNewReservationRowWhenRebookingAfterCancellation() {
        Fixture fixture = persistFixture("rebook");
        ReservationCreateResponse firstResponse = reservationService.reserve(
                fixture.member().getId(),
                fixture.classSession().getId()
        );

        reservationService.cancel(fixture.member().getId(), firstResponse.id());
        ReservationCreateResponse secondResponse = reservationService.reserve(
                fixture.member().getId(),
                fixture.classSession().getId()
        );
        entityManager.flush();
        entityManager.clear();

        Reservation firstReservation = reservationRepository.findById(
                firstResponse.id()
        ).orElseThrow();
        Reservation secondReservation = reservationRepository.findById(
                secondResponse.id()
        ).orElseThrow();
        ClassSession classSession = classSessionRepository.findById(
                fixture.classSession().getId()
        ).orElseThrow();
        assertThat(firstReservation.getId()).isNotEqualTo(secondReservation.getId());
        assertThat(firstReservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(firstReservation.getCancelledAt()).isEqualTo(NOW);
        assertThat(secondReservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(secondReservation.getCancelledAt()).isNull();
        assertThat(reservationRepository.findAll()).hasSize(2);
        assertThat(classSession.getReservedCount()).isEqualTo(1);
    }

    private Fixture persistFixture(String suffix) {
        Member member = memberRepository.save(new Member(
                "reservation-" + suffix,
                "encoded-password",
                "Member " + suffix,
                "010-0000-0000"
        ));
        Instructor instructor = instructorRepository.save(new Instructor(
                "Instructor " + suffix,
                null
        ));
        ClassSession classSession = classSessionRepository.save(new ClassSession(
                instructor,
                ClassType.REFORMER,
                LocalDateTime.of(2026, 8, 21, 15, 0),
                50,
                LocalDateTime.of(2026, 8, 12, 9, 0),
                4,
                ClassSessionStatus.SCHEDULED
        ));
        entityManager.flush();
        MemberPass memberPass = PersistentPassFixtures.issue(
                member,
                classSession.getStartAt().toLocalDate(),
                passProductRepository,
                memberPassRepository,
                memberPassHistoryRepository
        );
        return new Fixture(member, classSession, memberPass);
    }

    private record Fixture(Member member, ClassSession classSession, MemberPass memberPass) {
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
