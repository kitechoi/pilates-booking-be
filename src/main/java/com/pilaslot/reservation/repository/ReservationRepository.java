package com.pilaslot.reservation.repository;

import com.pilaslot.reservation.domain.Reservation;
import com.pilaslot.reservation.domain.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByIdAndMemberId(Long reservationId, Long memberId);

    @Query("""
            SELECT reservation.classSession.id
            FROM Reservation reservation
            WHERE reservation.id = :reservationId
              AND reservation.member.id = :memberId
            """)
    Optional<Long> findClassSessionIdByIdAndMemberId(
            @Param("reservationId") Long reservationId,
            @Param("memberId") Long memberId
    );

    @Query("""
            SELECT reservation
            FROM Reservation reservation
            JOIN FETCH reservation.classSession classSession
            JOIN FETCH classSession.instructor
            WHERE reservation.member.id = :memberId
              AND classSession.startAt >= :weekStart
              AND classSession.startAt < :weekEnd
            ORDER BY classSession.startAt ASC, reservation.id ASC
            """)
    List<Reservation> findAllWithClassSessionAndInstructorByMemberIdAndClassSessionWeek(
            @Param("memberId") Long memberId,
            @Param("weekStart") LocalDateTime weekStart,
            @Param("weekEnd") LocalDateTime weekEnd
    );

    @Query("""
            SELECT reservation
            FROM Reservation reservation
            JOIN FETCH reservation.classSession classSession
            JOIN FETCH classSession.instructor
            WHERE reservation.member.id = :memberId
              AND reservation.status = :status
              AND classSession.startAt >= :weekStart
              AND classSession.startAt < :weekEnd
            ORDER BY classSession.startAt ASC, reservation.id ASC
            """)
    List<Reservation> findAllWithClassSessionAndInstructorByMemberIdAndStatusAndClassSessionWeek(
            @Param("memberId") Long memberId,
            @Param("status") ReservationStatus status,
            @Param("weekStart") LocalDateTime weekStart,
            @Param("weekEnd") LocalDateTime weekEnd
    );

    boolean existsByMemberIdAndClassSessionIdAndStatus(
            Long memberId,
            Long classSessionId,
            ReservationStatus status
    );

    @Query("""
            SELECT COUNT(reservation)
            FROM Reservation reservation
            WHERE reservation.member.id = :memberId
              AND reservation.status = :status
              AND reservation.classSession.startAt >= :weekStart
              AND reservation.classSession.startAt < :weekEnd
            """)
    long countByMemberAndStatusInClassSessionWeek(
            @Param("memberId") Long memberId,
            @Param("status") ReservationStatus status,
            @Param("weekStart") LocalDateTime weekStart,
            @Param("weekEnd") LocalDateTime weekEnd
    );
}
