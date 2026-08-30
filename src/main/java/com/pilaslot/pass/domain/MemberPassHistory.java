package com.pilaslot.pass.domain;

import com.pilaslot.global.common.BaseTimeEntity;
import com.pilaslot.reservation.domain.Reservation;
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

@Getter
@Entity
@Table(name = "member_pass_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberPassHistory extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_pass_id", nullable = false)
    private MemberPass memberPass;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private Reservation reservation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MemberPassHistoryType type;

    @Column(name = "count_delta", nullable = false)
    private int countDelta;

    @Column(name = "remaining_count_after", nullable = false)
    private int remainingCountAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private MemberPassHistoryActorType actorType;

    @Column(length = 500)
    private String memo;

    private MemberPassHistory(
            MemberPass memberPass,
            Reservation reservation,
            MemberPassHistoryType type,
            int countDelta,
            int remainingCountAfter,
            MemberPassHistoryActorType actorType,
            String memo
    ) {
        this.memberPass = memberPass;
        this.reservation = reservation;
        this.type = type;
        this.countDelta = countDelta;
        this.remainingCountAfter = remainingCountAfter;
        this.actorType = actorType;
        this.memo = memo;
    }

    public static MemberPassHistory issued(MemberPass memberPass, MemberPassHistoryActorType actorType, String memo) {
        validateAdminMemo(actorType, memo);
        return new MemberPassHistory(
                memberPass, null, MemberPassHistoryType.ISSUED,
                memberPass.getInitialCount(), memberPass.getRemainingCount(), actorType, memo
        );
    }

    public static MemberPassHistory migrationOpening(MemberPass memberPass, String memo) {
        return new MemberPassHistory(
                memberPass, null, MemberPassHistoryType.MIGRATION_OPENING,
                memberPass.getRemainingCount(), memberPass.getRemainingCount(),
                MemberPassHistoryActorType.SYSTEM, memo
        );
    }

    public static MemberPassHistory reservationDebit(MemberPass memberPass, Reservation reservation) {
        return new MemberPassHistory(
                memberPass, reservation, MemberPassHistoryType.RESERVATION_DEBIT,
                -1, memberPass.getRemainingCount(), MemberPassHistoryActorType.MEMBER, null
        );
    }

    public static MemberPassHistory cancellationRefund(MemberPass memberPass, Reservation reservation) {
        return new MemberPassHistory(
                memberPass, reservation, MemberPassHistoryType.CANCELLATION_REFUND,
                1, memberPass.getRemainingCount(), MemberPassHistoryActorType.MEMBER, null
        );
    }

    private static void validateAdminMemo(MemberPassHistoryActorType actorType, String memo) {
        if (actorType == MemberPassHistoryActorType.ADMIN && (memo == null || memo.isBlank())) {
            throw new IllegalArgumentException("관리자 변경 이력에는 메모가 필요합니다.");
        }
    }
}

