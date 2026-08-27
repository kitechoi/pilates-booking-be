package com.pilaslot.pass.dto.response;

import com.pilaslot.pass.domain.MemberPass;
import com.pilaslot.pass.domain.MemberPassAvailability;
import com.pilaslot.pass.domain.MemberPassStatus;

import java.time.LocalDate;

public record MemberPassResponse(
        Long id,
        String productName,
        int pricePaid,
        int initialCount,
        int remainingCount,
        LocalDate validFrom,
        LocalDate expiresOn,
        MemberPassStatus status,
        MemberPassAvailability availability
) {
    public static MemberPassResponse from(MemberPass memberPass, LocalDate today) {
        return new MemberPassResponse(
                memberPass.getId(),
                memberPass.getProductNameSnapshot(),
                memberPass.getPricePaid(),
                memberPass.getInitialCount(),
                memberPass.getRemainingCount(),
                memberPass.getValidFrom(),
                memberPass.getExpiresOn(),
                memberPass.getStatus(),
                memberPass.availabilityAt(today)
        );
    }
}
