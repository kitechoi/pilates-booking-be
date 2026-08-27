package com.pilaslot.pass.service;

import com.pilaslot.pass.dto.response.MemberPassListResponse;
import com.pilaslot.pass.dto.response.MemberPassResponse;
import com.pilaslot.pass.repository.MemberPassRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class MemberPassQueryService {

    private final MemberPassRepository memberPassRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public MemberPassListResponse getMyPasses(
            Long memberId,
            LocalDate usableOn,
            boolean includeUnavailable
    ) {
        LocalDate today = LocalDate.now(clock);
        LocalDate eligibilityDate = usableOn == null ? today : usableOn;
        var responses = memberPassRepository.findAllByMemberIdOrderByExpiresOnAscIdAsc(memberId)
                .stream()
                .filter(memberPass -> includeUnavailable
                        || memberPass.isUsableFor(eligibilityDate))
                .map(memberPass -> MemberPassResponse.from(memberPass, today, eligibilityDate))
                .toList();
        return new MemberPassListResponse(responses);
    }
}
