package com.pilaslot.classsession.domain;

import java.time.LocalDateTime;

public final class ClassSessionPolicy {

    private static final int RESERVATION_DEADLINE_HOURS = 2;
    private static final int CANCELLATION_DEADLINE_HOURS = 8;

    private ClassSessionPolicy() {
    }

    public static LocalDateTime reservationDeadline(LocalDateTime startAt) {
        return startAt.minusHours(RESERVATION_DEADLINE_HOURS);
    }

    public static LocalDateTime cancellationDeadline(LocalDateTime startAt) {
        return startAt.minusHours(CANCELLATION_DEADLINE_HOURS);
    }
}
