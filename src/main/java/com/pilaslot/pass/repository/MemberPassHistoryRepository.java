package com.pilaslot.pass.repository;

import com.pilaslot.pass.domain.MemberPassHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemberPassHistoryRepository extends JpaRepository<MemberPassHistory, Long> {

    List<MemberPassHistory> findAllByMemberPassIdOrderByIdAsc(Long memberPassId);
}
