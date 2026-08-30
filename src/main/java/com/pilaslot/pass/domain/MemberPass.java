package com.pilaslot.pass.domain;

import com.pilaslot.global.common.BaseTimeEntity;
import com.pilaslot.member.domain.Member;
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

import java.time.LocalDate;

@Getter
@Entity
@Table(name = "member_pass")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberPass extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pass_product_id", nullable = false)
    private PassProduct passProduct;

    @Column(name = "product_name_snapshot", nullable = false, length = 100)
    private String productNameSnapshot;

    @Column(name = "price_paid", nullable = false)
    private int pricePaid;

    @Column(name = "initial_count", nullable = false)
    private int initialCount;

    @Column(name = "remaining_count", nullable = false)
    private int remainingCount;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "expires_on", nullable = false)
    private LocalDate expiresOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberPassStatus status;

    private MemberPass(
            Member member,
            PassProduct passProduct,
            String productNameSnapshot,
            int pricePaid,
            int initialCount,
            LocalDate validFrom,
            LocalDate expiresOn
    ) {
        if (member == null || passProduct == null || productNameSnapshot == null || productNameSnapshot.isBlank()) {
            throw new IllegalArgumentException("회원, 상품, 상품명 스냅샷은 필수입니다.");
        }
        if (pricePaid < 0 || initialCount <= 0 || validFrom == null || expiresOn == null
                || expiresOn.isBefore(validFrom)) {
            throw new IllegalArgumentException("회원 수강권 값이 올바르지 않습니다.");
        }
        this.member = member;
        this.passProduct = passProduct;
        this.productNameSnapshot = productNameSnapshot;
        this.pricePaid = pricePaid;
        this.initialCount = initialCount;
        this.remainingCount = initialCount;
        this.validFrom = validFrom;
        this.expiresOn = expiresOn;
        this.status = MemberPassStatus.ACTIVE;
    }

    public static MemberPass issue(
            Member member,
            PassProduct passProduct,
            int pricePaid,
            int initialCount,
            LocalDate validFrom,
            LocalDate expiresOn
    ) {
        return new MemberPass(
                member,
                passProduct,
                passProduct.getName(),
                pricePaid,
                initialCount,
                validFrom,
                expiresOn
        );
    }

    public boolean isUsableFor(LocalDate classDate) {
        return status == MemberPassStatus.ACTIVE
                && remainingCount > 0
                && !classDate.isBefore(validFrom)
                && !classDate.isAfter(expiresOn);
    }

    public MemberPassAvailability availabilityAt(LocalDate today) {
        if (status == MemberPassStatus.CANCELLED) {
            return MemberPassAvailability.CANCELLED;
        }
        if (today.isAfter(expiresOn)) {
            return MemberPassAvailability.EXPIRED;
        }
        if (remainingCount == 0) {
            return MemberPassAvailability.EXHAUSTED;
        }
        if (today.isBefore(validFrom)) {
            return MemberPassAvailability.NOT_STARTED;
        }
        return MemberPassAvailability.AVAILABLE;
    }

    public void debit() {
        if (remainingCount <= 0) {
            throw new IllegalStateException("수강권 잔여 횟수가 없습니다.");
        }
        remainingCount--;
    }

    public void refund() {
        if (remainingCount >= initialCount) {
            throw new IllegalStateException("수강권 잔여 횟수는 최초 횟수를 초과할 수 없습니다.");
        }
        remainingCount++;
    }
}

