package com.pilaslot.reservation.domain;

import com.pilaslot.classsession.domain.ClassSession;
import com.pilaslot.classsession.domain.ClassSessionStatus;
import com.pilaslot.classsession.domain.ClassType;
import com.pilaslot.instructor.domain.Instructor;
import com.pilaslot.member.domain.Member;
import com.pilaslot.pass.domain.MemberPass;
import com.pilaslot.reservation.domain.CancellationSource;
import com.pilaslot.support.PassFixtures;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationTest {

    @Test
    void reserveCreatesValidInitialState() {
        Member member = new Member("member-001", "encoded-password", "Member", "010-0000-0000");
        Instructor instructor = new Instructor("Instructor", null);
        ClassSession classSession = new ClassSession(
                instructor,
                ClassType.REFORMER,
                LocalDateTime.of(2026, 8, 22, 13, 0),
                50,
                LocalDateTime.of(2026, 8, 15, 13, 0),
                4,
                ClassSessionStatus.SCHEDULED
        );
        LocalDateTime reservedAt = LocalDateTime.of(2026, 8, 16, 10, 0);

        MemberPass memberPass = PassFixtures.memberPass(member, classSession.getStartAt().toLocalDate());
        Reservation reservation = Reservation.reserve(member, classSession, memberPass, reservedAt);

        assertThat(reservation.getMember()).isSameAs(member);
        assertThat(reservation.getClassSession()).isSameAs(classSession);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(reservation.getReservedAt()).isEqualTo(reservedAt);
        assertThat(reservation.getCancelledAt()).isNull();
    }

    @Test
    void cancelChangesOnlyCancellationState() {
        Member member = new Member("member-001", "encoded-password", "Member", "010-0000-0000");
        ClassSession classSession = new ClassSession(
                new Instructor("Instructor", null),
                ClassType.REFORMER,
                LocalDateTime.of(2026, 8, 22, 13, 0),
                50,
                LocalDateTime.of(2026, 8, 15, 13, 0),
                4,
                ClassSessionStatus.SCHEDULED
        );
        LocalDateTime reservedAt = LocalDateTime.of(2026, 8, 16, 10, 0);
        LocalDateTime cancelledAt = LocalDateTime.of(2026, 8, 19, 13, 0);
        MemberPass memberPass = PassFixtures.memberPass(member, classSession.getStartAt().toLocalDate());
        Reservation reservation = Reservation.reserve(member, classSession, memberPass, reservedAt);

        reservation.cancel(cancelledAt, CancellationSource.MEMBER);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelledAt()).isEqualTo(cancelledAt);
        assertThat(reservation.getReservedAt()).isEqualTo(reservedAt);
        assertThat(reservation.getMember()).isSameAs(member);
        assertThat(reservation.getClassSession()).isSameAs(classSession);
    }

    @Test
    void calculatesCancellationDeadlineEightHoursBeforeClassStart() {
        Reservation reservation = reservationStartingAt(
                LocalDateTime.of(2026, 8, 22, 13, 0)
        );

        assertThat(reservation.getCancellationDeadline())
                .isEqualTo(LocalDateTime.of(2026, 8, 22, 5, 0));
    }

    @Test
    void reservedReservationIsCancellableBeforeDeadline() {
        Reservation reservation = reservationStartingAt(
                LocalDateTime.of(2026, 8, 22, 13, 0)
        );

        assertThat(reservation.isCancellableAt(
                LocalDateTime.of(2026, 8, 22, 4, 59, 59)
        )).isTrue();
    }

    @Test
    void reservedReservationIsCancellableExactlyAtDeadline() {
        Reservation reservation = reservationStartingAt(
                LocalDateTime.of(2026, 8, 22, 13, 0)
        );

        assertThat(reservation.isCancellableAt(
                LocalDateTime.of(2026, 8, 22, 5, 0)
        )).isTrue();
    }

    @Test
    void reservedReservationIsNotCancellableAfterDeadline() {
        Reservation reservation = reservationStartingAt(
                LocalDateTime.of(2026, 8, 22, 13, 0)
        );

        assertThat(reservation.isCancellableAt(
                LocalDateTime.of(2026, 8, 22, 5, 0, 0, 1)
        )).isFalse();
    }

    @Test
    void cancelledReservationIsNotCancellableBeforeDeadline() {
        Reservation reservation = reservationStartingAt(
                LocalDateTime.of(2026, 8, 22, 13, 0)
        );
        reservation.cancel(LocalDateTime.of(2026, 8, 21, 10, 0), CancellationSource.MEMBER);

        assertThat(reservation.isCancellableAt(
                LocalDateTime.of(2026, 8, 22, 4, 0)
        )).isFalse();
    }

    private Reservation reservationStartingAt(LocalDateTime startAt) {
        Member member = new Member(
                "cancellable-member",
                "encoded-password",
                "Member",
                "010-0000-0000"
        );
        ClassSession classSession = new ClassSession(
                new Instructor("Instructor", null),
                ClassType.REFORMER,
                startAt,
                50,
                startAt.minusDays(7),
                4,
                ClassSessionStatus.SCHEDULED
        );
        return Reservation.reserve(
                member,
                classSession,
                PassFixtures.memberPass(member, startAt.toLocalDate()),
                startAt.minusDays(1)
        );
    }
}
