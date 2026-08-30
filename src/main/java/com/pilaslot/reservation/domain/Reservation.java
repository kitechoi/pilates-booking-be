package com.pilaslot.reservation.domain;

import com.pilaslot.classsession.domain.ClassSession;
import com.pilaslot.global.common.BaseTimeEntity;
import com.pilaslot.member.domain.Member;
import com.pilaslot.pass.domain.MemberPass;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "reservation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends BaseTimeEntity {

    private static final int CANCELLATION_DEADLINE_HOURS = 8;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "class_session_id", nullable = false)
    private ClassSession classSession;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_pass_id", nullable = false)
    private MemberPass memberPass;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    @Column(name = "reserved_at", nullable = false)
    private LocalDateTime reservedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_source", length = 20)
    private CancellationSource cancellationSource;

    private Reservation(
            Member member,
            ClassSession classSession,
            MemberPass memberPass,
            ReservationStatus status,
            LocalDateTime reservedAt,
            LocalDateTime cancelledAt
    ) {
        this.member = member;
        this.classSession = classSession;
        this.memberPass = memberPass;
        this.status = status;
        this.reservedAt = reservedAt;
        this.cancelledAt = cancelledAt;
        this.cancellationSource = null;
    }

    public static Reservation reserve(
            Member member,
            ClassSession classSession,
            MemberPass memberPass,
            LocalDateTime reservedAt
    ) {
        if (memberPass == null) {
            throw new IllegalArgumentException("예약에는 회원 수강권이 필요합니다.");
        }
        if (memberPass.getMember() != member
                && (memberPass.getMember().getId() == null
                || !memberPass.getMember().getId().equals(member.getId()))) {
            throw new IllegalArgumentException("예약 회원과 수강권 소유 회원이 일치해야 합니다.");
        }
        return new Reservation(
                member,
                classSession,
                memberPass,
                ReservationStatus.RESERVED,
                reservedAt,
                null
        );
    }

    public void cancel(LocalDateTime cancelledAt, CancellationSource cancellationSource) {
        if (cancellationSource == null) {
            throw new IllegalArgumentException("취소 주체가 필요합니다.");
        }
        this.status = ReservationStatus.CANCELLED;
        this.cancelledAt = cancelledAt;
        this.cancellationSource = cancellationSource;
    }

    public LocalDateTime getCancellationDeadline() {
        return classSession.getStartAt().minusHours(CANCELLATION_DEADLINE_HOURS);
    }

    public boolean isCancellableAt(LocalDateTime now) {
        return status == ReservationStatus.RESERVED
                && !now.isAfter(getCancellationDeadline());
    }
}
