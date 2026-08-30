package com.pilaslot.reservation.service;

import com.pilaslot.classsession.domain.ClassSession;
import com.pilaslot.classsession.domain.ClassSessionStatus;
import com.pilaslot.classsession.repository.ClassSessionRepository;
import com.pilaslot.global.exception.BusinessException;
import com.pilaslot.global.exception.ErrorCode;
import com.pilaslot.member.domain.Member;
import com.pilaslot.member.repository.MemberRepository;
import com.pilaslot.pass.domain.MemberPass;
import com.pilaslot.pass.domain.MemberPassHistory;
import com.pilaslot.pass.repository.MemberPassHistoryRepository;
import com.pilaslot.pass.repository.MemberPassRepository;
import com.pilaslot.reservation.domain.CancellationSource;
import com.pilaslot.reservation.domain.Reservation;
import com.pilaslot.reservation.domain.ReservationPolicy;
import com.pilaslot.reservation.domain.ReservationStatus;
import com.pilaslot.reservation.dto.response.ReservationCreateResponse;
import com.pilaslot.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ClassSessionRepository classSessionRepository;
    private final MemberRepository memberRepository;
    private final ReservationRepository reservationRepository;
    private final MemberPassRepository memberPassRepository;
    private final MemberPassHistoryRepository memberPassHistoryRepository;
    private final Clock clock;

    @Transactional
    public ReservationCreateResponse reserve(Long memberId, Long classSessionId) {
        Member member = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        ClassSession classSession = classSessionRepository.findByIdForUpdate(classSessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CLASS_SESSION_NOT_FOUND));
        validateClassSessionStatus(classSession);

        LocalDateTime now = LocalDateTime.now(clock);
        validateReservationTime(classSession, now);

        validateDuplicate(memberId, classSessionId);
        validateWeeklyLimit(memberId, classSession.getStartAt());
        validateCapacity(classSession);

        MemberPass memberPass = memberPassRepository.findFirstUsableForUpdate(
                        memberId,
                        classSession.getStartAt().toLocalDate()
                )
                .orElseThrow(() -> new BusinessException(ErrorCode.NO_USABLE_MEMBER_PASS));
        memberPass.debit();
        Reservation reservation = Reservation.reserve(member, classSession, memberPass, now);
        Reservation savedReservation = reservationRepository.save(reservation);
        memberPassHistoryRepository.save(MemberPassHistory.reservationDebit(memberPass, savedReservation));
        classSession.increaseReservedCount();

        return ReservationCreateResponse.from(savedReservation);
    }

    @Transactional
    public void cancel(Long memberId, Long reservationId) {
        Long classSessionId = reservationRepository
                .findClassSessionIdByIdAndMemberId(reservationId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        ClassSession classSession = classSessionRepository.findByIdForUpdate(classSessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CLASS_SESSION_NOT_FOUND));

        Reservation reservation = reservationRepository
                .findByIdAndMemberId(reservationId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        validateReservationStatus(reservation);

        LocalDateTime now = LocalDateTime.now(clock);
        validateCancellationTime(reservation, now);
        validateWeeklyCancellationLimit(memberId, classSession.getStartAt());

        MemberPass linkedMemberPass = reservation.getMemberPass();
        if (linkedMemberPass == null) {
            throw new BusinessException(ErrorCode.RESERVATION_MEMBER_PASS_NOT_ASSIGNED);
        }
        MemberPass memberPass = memberPassRepository.findByIdForUpdate(linkedMemberPass.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_MEMBER_PASS_NOT_ASSIGNED));
        memberPass.refund();
        reservation.cancel(now, CancellationSource.MEMBER);
        memberPassHistoryRepository.save(MemberPassHistory.cancellationRefund(memberPass, reservation));
        classSession.decreaseReservedCount();
    }

    private void validateClassSessionStatus(ClassSession classSession) {
        if (classSession.getStatus() != ClassSessionStatus.SCHEDULED) {
            throw new BusinessException(ErrorCode.CLASS_SESSION_CANCELLED);
        }
    }

    private void validateReservationTime(ClassSession classSession, LocalDateTime now) {
        if (now.isBefore(classSession.getReservationOpenAt())) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_OPEN);
        }
        if (now.isAfter(classSession.getReservationDeadline())) {
            throw new BusinessException(ErrorCode.RESERVATION_CLOSED);
        }
    }

    private void validateDuplicate(Long memberId, Long classSessionId) {
        boolean duplicate = reservationRepository.existsByMemberIdAndClassSessionIdAndStatus(
                memberId,
                classSessionId,
                ReservationStatus.RESERVED
        );
        if (duplicate) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESERVATION);
        }
    }

    private void validateWeeklyLimit(Long memberId, LocalDateTime classStartAt) {
        LocalDate weekStartDate = classStartAt.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDateTime weekStart = weekStartDate.atStartOfDay();
        LocalDateTime weekEnd = weekStart.plusWeeks(1);
        long activeReservationCount = reservationRepository.countByMemberAndStatusInClassSessionWeek(
                memberId,
                ReservationStatus.RESERVED,
                weekStart,
                weekEnd
        );
        if (ReservationPolicy.isWeeklyReservationLimitReached(activeReservationCount)) {
            throw new BusinessException(ErrorCode.WEEKLY_RESERVATION_LIMIT_EXCEEDED);
        }
    }

    private void validateCapacity(ClassSession classSession) {
        if (classSession.getReservedCount() >= classSession.getCapacity()) {
            throw new BusinessException(ErrorCode.CLASS_SESSION_FULL);
        }
    }

    private void validateReservationStatus(Reservation reservation) {
        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.RESERVATION_ALREADY_CANCELLED);
        }
    }

    private void validateCancellationTime(Reservation reservation, LocalDateTime now) {
        if (now.isAfter(reservation.getCancellationDeadline())) {
            throw new BusinessException(ErrorCode.CANCELLATION_CLOSED);
        }
    }

    private void validateWeeklyCancellationLimit(Long memberId, LocalDateTime classStartAt) {
        LocalDate weekStartDate = classStartAt.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDateTime weekStart = weekStartDate.atStartOfDay();
        LocalDateTime weekEnd = weekStart.plusWeeks(1);
        long cancellationCount = reservationRepository.countByMemberAndStatusAndCancellationSourceInClassSessionWeek(
                memberId,
                ReservationStatus.CANCELLED,
                CancellationSource.MEMBER,
                weekStart,
                weekEnd
        );
        if (ReservationPolicy.isWeeklyCancellationLimitReached(cancellationCount)) {
            throw new BusinessException(ErrorCode.WEEKLY_CANCELLATION_LIMIT_EXCEEDED);
        }
    }
}
