package com.pilaslot.reservation.dto.response;

import com.pilaslot.reservation.domain.Reservation;
import com.pilaslot.reservation.domain.ReservationStatus;

import java.time.LocalDateTime;

public record ReservationCreateResponse(
        Long id,
        Long classSessionId,
        Long memberPassId,
        String passProductName,
        int remainingPassCount,
        ReservationStatus status,
        LocalDateTime reservedAt
) {

    public static ReservationCreateResponse from(Reservation reservation) {
        return new ReservationCreateResponse(
                reservation.getId(),
                reservation.getClassSession().getId(),
                reservation.getMemberPass().getId(),
                reservation.getMemberPass().getProductNameSnapshot(),
                reservation.getMemberPass().getRemainingCount(),
                reservation.getStatus(),
                reservation.getReservedAt()
        );
    }
}
