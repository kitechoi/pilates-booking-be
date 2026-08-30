package com.pilaslot.pass.domain;

import com.pilaslot.member.domain.Member;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemberPassTest {

    private static final LocalDate CLASS_DATE = LocalDate.of(2026, 8, 21);

    @Test
    void isUsableOnBothValidityBoundaries() {
        MemberPass memberPass = memberPass(2);

        assertThat(memberPass.isUsableFor(CLASS_DATE.minusDays(1))).isTrue();
        assertThat(memberPass.isUsableFor(CLASS_DATE.plusDays(1))).isTrue();
    }

    @Test
    void debitAndRefundPreserveCountBounds() {
        MemberPass memberPass = memberPass(1);

        memberPass.debit();
        assertThat(memberPass.getRemainingCount()).isZero();
        assertThatThrownBy(memberPass::debit).isInstanceOf(IllegalStateException.class);

        memberPass.refund();
        assertThat(memberPass.getRemainingCount()).isEqualTo(1);
        assertThatThrownBy(memberPass::refund).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void availabilityUsesTodayButReservationEligibilityUsesClassDate() {
        MemberPass memberPass = memberPass(1);

        assertThat(memberPass.availabilityAt(CLASS_DATE.minusDays(2)))
                .isEqualTo(MemberPassAvailability.NOT_STARTED);
        assertThat(memberPass.isUsableFor(CLASS_DATE)).isTrue();
    }

    private MemberPass memberPass(int count) {
        Member member = new Member("member", "password", "회원", "010-0000-0000");
        PassProduct product = new PassProduct("PASS", "수강권", 100000, count, 3);
        return MemberPass.issue(
                member,
                product,
                100000,
                count,
                CLASS_DATE.minusDays(1),
                CLASS_DATE.plusDays(1)
        );
    }
}
