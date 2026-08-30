package com.pilaslot.reservation.service;

import com.pilaslot.global.exception.BusinessException;
import com.pilaslot.global.exception.ErrorCode;
import com.pilaslot.reservation.domain.Reservation;
import com.pilaslot.reservation.domain.ReservationPolicy;
import com.pilaslot.reservation.domain.ReservationStatus;
import com.pilaslot.reservation.domain.CancellationSource;
import com.pilaslot.reservation.dto.response.MyReservationListResponse;
import com.pilaslot.reservation.dto.response.MyReservationResponse;
import com.pilaslot.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReservationQueryService {

    private final ReservationRepository reservationRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public MyReservationListResponse getMyReservations(
            Long memberId,
            LocalDate weekStart,
            ReservationStatus status
    ) {
        validateWeekStart(weekStart);
        LocalDateTime rangeStart = weekStart.atStartOfDay();
        LocalDateTime rangeEnd = weekStart.plusWeeks(1).atStartOfDay();
        LocalDateTime now = LocalDateTime.now(clock);
        long weeklyCancellationCount =
                reservationRepository.countByMemberAndStatusAndCancellationSourceInClassSessionWeek(
                        memberId,
                        ReservationStatus.CANCELLED,
                        CancellationSource.MEMBER,
                        rangeStart,
                        rangeEnd
                );
        boolean weeklyCancellationAvailable =
                !ReservationPolicy.isWeeklyCancellationLimitReached(weeklyCancellationCount);

        List<Reservation> reservations = status == null
                ? reservationRepository
                .findAllWithClassSessionAndInstructorByMemberIdAndClassSessionWeek(
                        memberId,
                        rangeStart,
                        rangeEnd
                )
                : reservationRepository
                .findAllWithClassSessionAndInstructorByMemberIdAndStatusAndClassSessionWeek(
                        memberId,
                        status,
                        rangeStart,
                        rangeEnd
                );
        List<MyReservationResponse> responses = reservations.stream()
                .map(reservation -> {
                    boolean cancellable = reservation.isCancellableAt(now)
                            && weeklyCancellationAvailable;
                    return MyReservationResponse.from(reservation, cancellable);
                })
                .toList();

        return new MyReservationListResponse(weekStart, responses);
    }

    private void validateWeekStart(LocalDate weekStart) {
        if (weekStart == null || weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new BusinessException(ErrorCode.INVALID_WEEK_START);
        }
    }
}
