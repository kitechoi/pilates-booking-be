package com.pilaslot.pass.service;

import com.pilaslot.pass.domain.MemberPassAvailability;
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
    public MemberPassListResponse getMyPasses(Long memberId, boolean includeUnavailable) {
        LocalDate today = LocalDate.now(clock);
        var responses = memberPassRepository.findAllByMemberIdOrderByExpiresOnAscIdAsc(memberId)
                .stream()
                .filter(memberPass -> includeUnavailable
                        || memberPass.availabilityAt(today) == MemberPassAvailability.AVAILABLE)
                .map(memberPass -> MemberPassResponse.from(memberPass, today))
                .toList();
        return new MemberPassListResponse(responses);
    }
}
