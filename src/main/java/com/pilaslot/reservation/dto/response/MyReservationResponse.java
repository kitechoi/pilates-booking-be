package com.pilaslot.reservation.dto.response;

import com.pilaslot.classsession.domain.ClassSession;
import com.pilaslot.classsession.domain.ClassType;
import com.pilaslot.instructor.domain.Instructor;
import com.pilaslot.reservation.domain.Reservation;
import com.pilaslot.reservation.domain.ReservationStatus;

import java.time.LocalDateTime;

public record MyReservationResponse(
        Long id,
        ReservationStatus status,
        LocalDateTime reservedAt,
        LocalDateTime cancelledAt,
        boolean cancellable,
        LocalDateTime cancellationDeadline,
        Long memberPassId,
        String passProductName,
        ClassSessionSummary classSession
) {

    public static MyReservationResponse from(
            Reservation reservation,
            boolean cancellable
    ) {
        return new MyReservationResponse(
                reservation.getId(),
                reservation.getStatus(),
                reservation.getReservedAt(),
                reservation.getCancelledAt(),
                cancellable,
                reservation.getCancellationDeadline(),
                reservation.getMemberPass() == null ? null : reservation.getMemberPass().getId(),
                reservation.getMemberPass() == null ? null : reservation.getMemberPass().getProductNameSnapshot(),
                ClassSessionSummary.from(reservation.getClassSession())
        );
    }

    public record ClassSessionSummary(
            Long classSessionId,
            ClassType classType,
            LocalDateTime startAt,
            LocalDateTime endAt,
            InstructorResponse instructor
    ) {

        private static ClassSessionSummary from(ClassSession classSession) {
            return new ClassSessionSummary(
                    classSession.getId(),
                    classSession.getClassType(),
                    classSession.getStartAt(),
                    classSession.getEndAt(),
                    InstructorResponse.from(classSession.getInstructor())
            );
        }
    }

    public record InstructorResponse(
            Long instructorId,
            String name,
            String profileImageUrl
    ) {

        private static InstructorResponse from(Instructor instructor) {
            return new InstructorResponse(
                    instructor.getId(),
                    instructor.getName(),
                    instructor.getProfileImageUrl()
            );
        }
    }
}
