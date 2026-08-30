package com.pilaslot.pass.repository;

import com.pilaslot.member.domain.Member;
import com.pilaslot.member.repository.MemberRepository;
import com.pilaslot.pass.domain.MemberPass;
import com.pilaslot.pass.domain.PassProduct;
import com.pilaslot.support.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@ActiveProfiles("test")
@SpringBootTest
@Import(PostgreSqlTestContainerConfiguration.class)
class MemberPassRepositoryIntegrationTest {

    private static final LocalDate CLASS_DATE = LocalDate.of(2026, 8, 21);

    @Autowired private MemberRepository memberRepository;
    @Autowired private PassProductRepository passProductRepository;
    @Autowired private MemberPassRepository memberPassRepository;

    private Member member;
    private PassProduct product;

    @BeforeEach
    void setUp() {
        member = memberRepository.save(new Member(
                "fefo-" + UUID.randomUUID(), "password", "만료 임박 우선 회원", "010-0000-0000"
        ));
        product = passProductRepository.save(new PassProduct(
                "FEFO_" + UUID.randomUUID(), "만료 임박 우선 상품", 100000, 10, 90
        ));
    }

    @Test
    void selectsPassWithEarliestExpiryFirst() {
        MemberPass laterExpiry = issue(CLASS_DATE.minusDays(10), CLASS_DATE.plusDays(20));
        MemberPass earliestExpiry = issue(CLASS_DATE.minusDays(10), CLASS_DATE.plusDays(5));

        MemberPass selected = selectFirstUsable();

        assertThat(selected.getId()).isEqualTo(earliestExpiry.getId());
        assertThat(selected.getId()).isNotEqualTo(laterExpiry.getId());
        assertThat(selected.isUsableFor(CLASS_DATE)).isTrue();
    }

    @Test
    void selectsEarlierStartWhenExpiryIsSame() {
        LocalDate expiresOn = CLASS_DATE.plusDays(20);
        MemberPass laterStart = issue(CLASS_DATE.minusDays(5), expiresOn);
        MemberPass earlierStart = issue(CLASS_DATE.minusDays(10), expiresOn);

        MemberPass selected = selectFirstUsable();

        assertThat(selected.getId()).isEqualTo(earlierStart.getId());
        assertThat(selected.getId()).isNotEqualTo(laterStart.getId());
    }

    @Test
    void selectsLowerIdWhenExpiryAndStartAreSame() {
        LocalDate validFrom = CLASS_DATE.minusDays(10);
        LocalDate expiresOn = CLASS_DATE.plusDays(20);
        MemberPass lowerId = issue(validFrom, expiresOn);
        MemberPass higherId = issue(validFrom, expiresOn);

        MemberPass selected = selectFirstUsable();

        assertThat(selected.getId()).isEqualTo(lowerId.getId());
        assertThat(selected.getId()).isNotEqualTo(higherId.getId());
    }

    @Test
    void excludesPassesThatDomainConsidersUnusable() {
        MemberPass expired = issue(CLASS_DATE.minusDays(20), CLASS_DATE.minusDays(1));
        MemberPass notStarted = issue(CLASS_DATE.plusDays(1), CLASS_DATE.plusDays(20));
        MemberPass exhausted = issue(CLASS_DATE.minusDays(10), CLASS_DATE.plusDays(10));
        for (int i = 0; i < exhausted.getInitialCount(); i++) {
            exhausted.debit();
        }
        MemberPass usable = issue(CLASS_DATE.minusDays(5), CLASS_DATE.plusDays(30));

        MemberPass selected = selectFirstUsable();

        assertThat(expired.isUsableFor(CLASS_DATE)).isFalse();
        assertThat(notStarted.isUsableFor(CLASS_DATE)).isFalse();
        assertThat(exhausted.isUsableFor(CLASS_DATE)).isFalse();
        assertThat(selected.getId()).isEqualTo(usable.getId());
    }

    @Test
    void returnsEmptyWhenNoPassIsUsable() {
        issue(CLASS_DATE.minusDays(20), CLASS_DATE.minusDays(1));
        issue(CLASS_DATE.plusDays(1), CLASS_DATE.plusDays(20));

        Optional<MemberPass> selected = memberPassRepository.findFirstUsableForUpdate(
                member.getId(),
                CLASS_DATE
        );

        assertThat(selected).isEmpty();
    }

    private MemberPass issue(LocalDate validFrom, LocalDate expiresOn) {
        return memberPassRepository.saveAndFlush(MemberPass.issue(
                member,
                product,
                100000,
                10,
                validFrom,
                expiresOn
        ));
    }

    private MemberPass selectFirstUsable() {
        return memberPassRepository.findFirstUsableForUpdate(member.getId(), CLASS_DATE)
                .orElseThrow();
    }
}
