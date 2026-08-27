package com.pilaslot.pass.repository;

import com.pilaslot.pass.domain.MemberPass;
import com.pilaslot.pass.domain.MemberPassStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MemberPassRepository extends JpaRepository<MemberPass, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT memberPass FROM MemberPass memberPass WHERE memberPass.id = :memberPassId")
    Optional<MemberPass> findByIdForUpdate(@Param("memberPassId") Long memberPassId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("""
            SELECT memberPass
            FROM MemberPass memberPass
            WHERE memberPass.member.id = :memberId
              AND memberPass.status = :status
              AND memberPass.remainingCount > 0
              AND memberPass.validFrom <= :classDate
              AND memberPass.expiresOn >= :classDate
            ORDER BY memberPass.expiresOn ASC, memberPass.validFrom ASC, memberPass.id ASC
            """)
    List<MemberPass> findUsableForUpdate(
            @Param("memberId") Long memberId,
            @Param("status") MemberPassStatus status,
            @Param("classDate") LocalDate classDate,
            Pageable pageable
    );

    @Query("""
            SELECT memberPass
            FROM MemberPass memberPass
            WHERE memberPass.member.id = :memberId
            ORDER BY memberPass.expiresOn ASC, memberPass.id ASC
            """)
    List<MemberPass> findAllByMemberIdOrderByExpiresOnAscIdAsc(@Param("memberId") Long memberId);
}
