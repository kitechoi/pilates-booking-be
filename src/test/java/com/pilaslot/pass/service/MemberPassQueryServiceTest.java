package com.pilaslot.pass.service;

import com.pilaslot.member.domain.Member;
import com.pilaslot.pass.domain.MemberPass;
import com.pilaslot.pass.domain.MemberPassAvailability;
import com.pilaslot.pass.domain.PassProduct;
import com.pilaslot.pass.repository.MemberPassRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class MemberPassQueryServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 19);

    @Mock
    private MemberPassRepository memberPassRepository;

    private MemberPassQueryService service;

    @BeforeEach
    void setUp() {
        ZoneId zone = ZoneId.of("Asia/Seoul");
        Clock clock = Clock.fixed(TODAY.atStartOfDay(zone).toInstant(), zone);
        service = new MemberPassQueryService(memberPassRepository, clock);
    }

    @Test
    void futureUsableOnIncludesPassThatHasNotStartedToday() {
        Member member = new Member("member", "password", "회원", "010-0000-0000");
        PassProduct product = new PassProduct("FUTURE", "미래 수강권", 100000, 10, 30);
        LocalDate usableOn = TODAY.plusDays(5);
        MemberPass memberPass = MemberPass.issue(
                member,
                product,
                100000,
                10,
                usableOn,
                usableOn.plusDays(30)
        );
        given(memberPassRepository.findAllByMemberIdOrderByExpiresOnAscIdAsc(1L))
                .willReturn(List.of(memberPass));

        var response = service.getMyPasses(1L, usableOn, false);

        assertThat(response.memberPasses()).singleElement().satisfies(pass -> {
            assertThat(pass.availability()).isEqualTo(MemberPassAvailability.NOT_STARTED);
            assertThat(pass.usable()).isTrue();
        });
    }
}
