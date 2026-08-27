package com.pilaslot.pass.repository;

import com.pilaslot.member.domain.Member;
import com.pilaslot.member.repository.MemberRepository;
import com.pilaslot.pass.domain.MemberPass;
import com.pilaslot.pass.domain.MemberPassStatus;
import com.pilaslot.pass.domain.PassProduct;
import com.pilaslot.support.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@ActiveProfiles("test")
@SpringBootTest
@Import(PostgreSqlTestContainerConfiguration.class)
class MemberPassRepositoryIntegrationTest {

    @Autowired private MemberRepository memberRepository;
    @Autowired private PassProductRepository passProductRepository;
    @Autowired private MemberPassRepository memberPassRepository;

    @Test
    void selectsUsablePassByExpiryThenStartThenIdWithoutFetchingProduct() {
        Member member = memberRepository.save(new Member(
                "fefo-" + UUID.randomUUID(), "password", "FEFO 회원", "010-0000-0000"
        ));
        PassProduct product = passProductRepository.save(new PassProduct(
                "FEFO_" + UUID.randomUUID(), "FEFO 상품", 100000, 10, 90
        ));
        LocalDate classDate = LocalDate.of(2026, 8, 21);

        MemberPass later = memberPassRepository.save(MemberPass.issue(
                member, product, 100000, 10,
                classDate.minusDays(10), classDate.plusDays(20)
        ));
        MemberPass earliest = memberPassRepository.save(MemberPass.issue(
                member, product, 100000, 10,
                classDate.minusDays(5), classDate.plusDays(5)
        ));
        memberPassRepository.flush();

        MemberPass selected = memberPassRepository.findUsableForUpdate(
                member.getId(),
                MemberPassStatus.ACTIVE,
                classDate,
                PageRequest.of(0, 1)
        ).get(0);

        assertThat(selected.getId()).isEqualTo(earliest.getId());
        assertThat(selected.getId()).isNotEqualTo(later.getId());
    }
}
