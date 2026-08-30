package com.pilaslot.support;

import com.pilaslot.member.domain.Member;
import com.pilaslot.pass.domain.MemberPass;
import com.pilaslot.pass.domain.MemberPassHistory;
import com.pilaslot.pass.domain.MemberPassHistoryActorType;
import com.pilaslot.pass.domain.PassProduct;
import com.pilaslot.pass.repository.MemberPassHistoryRepository;
import com.pilaslot.pass.repository.MemberPassRepository;
import com.pilaslot.pass.repository.PassProductRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.UUID;

public final class PersistentPassFixtures {

    private PersistentPassFixtures() {
    }

    public static MemberPass issue(
            Member member,
            LocalDate classDate,
            PassProductRepository passProductRepository,
            MemberPassRepository memberPassRepository,
            MemberPassHistoryRepository historyRepository
    ) {
        PassProduct product = passProductRepository.save(new PassProduct(
                "TEST_" + UUID.randomUUID(),
                "테스트 30회권",
                600000,
                30,
                90
        ));
        MemberPass memberPass = memberPassRepository.save(MemberPass.issue(
                member,
                product,
                600000,
                30,
                classDate.minusDays(30),
                classDate.plusDays(60)
        ));
        historyRepository.save(MemberPassHistory.issued(
                memberPass,
                MemberPassHistoryActorType.SYSTEM,
                null
        ));
        return memberPass;
    }

    public static MemberPass issueAtomically(
            Member member,
            LocalDate classDate,
            PassProductRepository passProductRepository,
            MemberPassRepository memberPassRepository,
            MemberPassHistoryRepository historyRepository,
            PlatformTransactionManager transactionManager
    ) {
        return new TransactionTemplate(transactionManager).execute(status -> issue(
                member,
                classDate,
                passProductRepository,
                memberPassRepository,
                historyRepository
        ));
    }
}
