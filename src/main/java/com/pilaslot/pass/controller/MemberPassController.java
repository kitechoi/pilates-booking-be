package com.pilaslot.pass.controller;

import com.pilaslot.global.security.AuthenticatedMember;
import com.pilaslot.pass.dto.response.MemberPassListResponse;
import com.pilaslot.pass.service.MemberPassQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/member-passes")
public class MemberPassController {

    private final MemberPassQueryService memberPassQueryService;

    @GetMapping
    public MemberPassListResponse getMyPasses(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate usableOn,
            @RequestParam(defaultValue = "false") boolean includeUnavailable,
            @AuthenticationPrincipal AuthenticatedMember authenticatedMember
    ) {
        return memberPassQueryService.getMyPasses(
                authenticatedMember.memberId(),
                usableOn,
                includeUnavailable
        );
    }
}
