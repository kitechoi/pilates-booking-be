package com.pilaslot.support;

import com.pilaslot.member.domain.Member;
import com.pilaslot.pass.domain.MemberPass;
import com.pilaslot.pass.domain.PassProduct;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

public final class PassFixtures {

    private PassFixtures() {
    }

    public static MemberPass memberPass(Member member, LocalDate classDate) {
        PassProduct product = new PassProduct("TEST_30", "테스트 30회권", 600000, 30, 90);
        ReflectionTestUtils.setField(product, "id", 100L);
        MemberPass memberPass = MemberPass.issue(
                member,
                product,
                600000,
                30,
                classDate.minusDays(30),
                classDate.plusDays(60)
        );
        ReflectionTestUtils.setField(memberPass, "id", 200L);
        return memberPass;
    }
}
