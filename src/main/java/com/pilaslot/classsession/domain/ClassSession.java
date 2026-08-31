package com.pilaslot.classsession.domain;

import com.pilaslot.global.common.BaseTimeEntity;
import com.pilaslot.instructor.domain.Instructor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "class_session")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClassSession extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instructor_id", nullable = false)
    private Instructor instructor;

    @Enumerated(EnumType.STRING)
    @Column(name = "class_type", nullable = false)
    private ClassType classType;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes;

    @Column(name = "reservation_open_at", nullable = false)
    private LocalDateTime reservationOpenAt;

    @Column(nullable = false)
    private Integer capacity;

    @Column(name = "reserved_count", nullable = false)
    private Integer reservedCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClassSessionStatus status;

    public ClassSession(
            Instructor instructor,
            ClassType classType,
            LocalDateTime startAt,
            Integer durationMinutes,
            LocalDateTime reservationOpenAt,
            Integer capacity,
            ClassSessionStatus status
    ) {
        this.instructor = instructor;
        this.classType = classType;
        this.startAt = startAt;
        this.durationMinutes = durationMinutes;
        this.reservationOpenAt = reservationOpenAt;
        this.capacity = capacity;
        this.reservedCount = 0;
        this.status = status;
    }

    public void increaseReservedCount() {
        this.reservedCount++;
    }

    public void decreaseReservedCount() {
        this.reservedCount--;
    }

    public LocalDateTime getReservationDeadline() {
        return ClassSessionPolicy.reservationDeadline(startAt);
    }

    public LocalDateTime getCancellationDeadline() {
        return ClassSessionPolicy.cancellationDeadline(startAt);
    }

    public LocalDateTime getEndAt() {
        return startAt.plusMinutes(durationMinutes);
    }

    public int getRemainingCount() {
        return capacity - reservedCount;
    }
}
