package com.pilaslot.reservation.service;

import com.pilaslot.classsession.domain.ClassSession;
import com.pilaslot.classsession.domain.ClassSessionStatus;
import com.pilaslot.classsession.domain.ClassType;
import com.pilaslot.global.exception.BusinessException;
import com.pilaslot.global.exception.ErrorCode;
import com.pilaslot.instructor.domain.Instructor;
import com.pilaslot.member.domain.Member;
import com.pilaslot.reservation.domain.Reservation;
import com.pilaslot.reservation.domain.ReservationStatus;
import com.pilaslot.reservation.domain.CancellationSource;
import com.pilaslot.support.PassFixtures;
import com.pilaslot.reservation.dto.response.MyReservationListResponse;
import com.pilaslot.reservation.dto.response.MyReservationResponse;
import com.pilaslot.reservation.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReservationQueryServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final LocalDate WEEK_START = LocalDate.of(2026, 8, 17);
    private static final LocalDateTime RANGE_START = WEEK_START.atStartOfDay();
    private static final LocalDateTime RANGE_END = WEEK_START.plusWeeks(1).atStartOfDay();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 19, 13, 0);
    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");

    @Mock
    private ReservationRepository reservationRepository;

    private ReservationQueryService reservationQueryService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZONE_ID).toInstant(), ZONE_ID);
        reservationQueryService = new ReservationQueryService(reservationRepository, clock);
    }

    @Test
    void returnsAllStatusesIncludingCancellationAndRebookingHistory() {
        ClassSession classSession = classSession(10L, LocalDateTime.of(2026, 8, 21, 15, 0));
        Reservation cancelled = reservation(55L, classSession, ReservationStatus.CANCELLED);
        Reservation reserved = reservation(72L, classSession, ReservationStatus.RESERVED);
        given(reservationRepository
                .findAllWithClassSessionAndInstructorByMemberIdAndClassSessionWeek(
                        MEMBER_ID,
                        RANGE_START,
                        RANGE_END
                )).willReturn(List.of(cancelled, reserved));
        given(reservationRepository.countByMemberAndStatusAndCancellationSourceInClassSessionWeek(
                MEMBER_ID,
                ReservationStatus.CANCELLED,
                CancellationSource.MEMBER,
                RANGE_START,
                RANGE_END
        )).willReturn(6L);

        MyReservationListResponse response = reservationQueryService.getMyReservations(
                MEMBER_ID,
                WEEK_START,
                null
        );

        assertThat(response.weekStart()).isEqualTo(WEEK_START);
        assertThat(response.reservations())
                .extracting(MyReservationResponse::id)
                .containsExactly(55L, 72L);
        assertThat(response.reservations())
                .extracting(MyReservationResponse::status)
                .containsExactly(ReservationStatus.CANCELLED, ReservationStatus.RESERVED);
        assertThat(response.reservations())
                .extracting(MyReservationResponse::cancellable)
                .containsExactly(false, true);
        assertThat(response.reservations())
                .extracting(MyReservationResponse::cancellationDeadline)
                .containsOnly(LocalDateTime.of(2026, 8, 21, 7, 0));
        verify(reservationRepository, times(1)).countByMemberAndStatusAndCancellationSourceInClassSessionWeek(
                MEMBER_ID,
                ReservationStatus.CANCELLED,
                CancellationSource.MEMBER,
                RANGE_START,
                RANGE_END
        );
    }

    @Test
    void filtersReservedReservations() {
        Reservation reserved = reservation(
                55L,
                classSession(10L, LocalDateTime.of(2026, 8, 21, 15, 0)),
                ReservationStatus.RESERVED
        );
        given(reservationRepository
                .findAllWithClassSessionAndInstructorByMemberIdAndStatusAndClassSessionWeek(
                        MEMBER_ID,
                        ReservationStatus.RESERVED,
                        RANGE_START,
                        RANGE_END
                )).willReturn(List.of(reserved));

        MyReservationListResponse response = reservationQueryService.getMyReservations(
                MEMBER_ID,
                WEEK_START,
                ReservationStatus.RESERVED
        );

        assertThat(response.reservations())
                .extracting(MyReservationResponse::status)
                .containsExactly(ReservationStatus.RESERVED);
    }

    @Test
    void filtersCancelledReservations() {
        Reservation cancelled = reservation(
                55L,
                classSession(10L, LocalDateTime.of(2026, 8, 21, 15, 0)),
                ReservationStatus.CANCELLED
        );
        given(reservationRepository
                .findAllWithClassSessionAndInstructorByMemberIdAndStatusAndClassSessionWeek(
                        MEMBER_ID,
                        ReservationStatus.CANCELLED,
                        RANGE_START,
                        RANGE_END
                )).willReturn(List.of(cancelled));

        MyReservationListResponse response = reservationQueryService.getMyReservations(
                MEMBER_ID,
                WEEK_START,
                ReservationStatus.CANCELLED
        );

        assertThat(response.reservations())
                .extracting(MyReservationResponse::status)
                .containsExactly(ReservationStatus.CANCELLED);
    }

    @Test
    void rejectsNonMondayWeekStart() {
        assertThatThrownBy(() -> reservationQueryService.getMyReservations(
                MEMBER_ID,
                WEEK_START.plusDays(1),
                null
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_WEEK_START);
    }

    @Test
    void mapsReservedReservationAsCancellableExactlyAtDeadline() {
        Reservation reserved = reservation(
                55L,
                classSession(10L, NOW.plusHours(8)),
                ReservationStatus.RESERVED
        );
        given(reservationRepository
                .findAllWithClassSessionAndInstructorByMemberIdAndClassSessionWeek(
                        MEMBER_ID,
                        RANGE_START,
                        RANGE_END
                )).willReturn(List.of(reserved));
        given(reservationRepository.countByMemberAndStatusAndCancellationSourceInClassSessionWeek(
                MEMBER_ID,
                ReservationStatus.CANCELLED,
                CancellationSource.MEMBER,
                RANGE_START,
                RANGE_END
        )).willReturn(6L);

        MyReservationListResponse response = reservationQueryService.getMyReservations(
                MEMBER_ID,
                WEEK_START,
                null
        );

        assertThat(response.reservations()).singleElement()
                .extracting(MyReservationResponse::cancellable)
                .isEqualTo(true);
        verify(reservationRepository, times(1)).countByMemberAndStatusAndCancellationSourceInClassSessionWeek(
                MEMBER_ID,
                ReservationStatus.CANCELLED,
                CancellationSource.MEMBER,
                RANGE_START,
                RANGE_END
        );
    }

    @Test
    void weeklyCancellationLimitMakesEveryReservationNotCancellable() {
        ClassSession classSession = classSession(10L, LocalDateTime.of(2026, 8, 21, 15, 0));
        Reservation reserved = reservation(55L, classSession, ReservationStatus.RESERVED);
        Reservation cancelled = reservation(72L, classSession, ReservationStatus.CANCELLED);
        given(reservationRepository
                .findAllWithClassSessionAndInstructorByMemberIdAndClassSessionWeek(
                        MEMBER_ID,
                        RANGE_START,
                        RANGE_END
                )).willReturn(List.of(reserved, cancelled));
        given(reservationRepository.countByMemberAndStatusAndCancellationSourceInClassSessionWeek(
                MEMBER_ID,
                ReservationStatus.CANCELLED,
                CancellationSource.MEMBER,
                RANGE_START,
                RANGE_END
        )).willReturn(7L);

        MyReservationListResponse response = reservationQueryService.getMyReservations(
                MEMBER_ID,
                WEEK_START,
                null
        );

        assertThat(response.reservations())
                .extracting(MyReservationResponse::cancellable)
                .containsExactly(false, false);
        verify(reservationRepository, times(1)).countByMemberAndStatusAndCancellationSourceInClassSessionWeek(
                MEMBER_ID,
                ReservationStatus.CANCELLED,
                CancellationSource.MEMBER,
                RANGE_START,
                RANGE_END
        );
    }

    @Test
    void mapsReservedReservationAsNotCancellableAfterDeadline() {
        Reservation reserved = reservation(
                55L,
                classSession(10L, NOW.plusHours(8).minusNanos(1)),
                ReservationStatus.RESERVED
        );
        given(reservationRepository
                .findAllWithClassSessionAndInstructorByMemberIdAndClassSessionWeek(
                        MEMBER_ID,
                        RANGE_START,
                        RANGE_END
                )).willReturn(List.of(reserved));

        MyReservationListResponse response = reservationQueryService.getMyReservations(
                MEMBER_ID,
                WEEK_START,
                null
        );

        assertThat(response.reservations()).singleElement()
                .extracting(MyReservationResponse::cancellable)
                .isEqualTo(false);
    }

    private Reservation reservation(
            Long reservationId,
            ClassSession classSession,
            ReservationStatus status
    ) {
        Member member = new Member("member", "encoded-password", "회원", "010-0000-0000");
        ReflectionTestUtils.setField(member, "id", MEMBER_ID);
        Reservation reservation = Reservation.reserve(
                member,
                classSession,
                PassFixtures.memberPass(member, classSession.getStartAt().toLocalDate()),
                NOW.minusDays(1)
        );
        ReflectionTestUtils.setField(reservation, "id", reservationId);
        if (status == ReservationStatus.CANCELLED) {
            reservation.cancel(NOW.minusHours(1), CancellationSource.MEMBER);
        }
        return reservation;
    }

    private ClassSession classSession(Long classSessionId, LocalDateTime startAt) {
        Instructor instructor = new Instructor(
                "김라라",
                "https://example.com/instructors/kim.jpg"
        );
        ReflectionTestUtils.setField(instructor, "id", 3L);
        ClassSession classSession = new ClassSession(
                instructor,
                ClassType.CHAIR_BARREL,
                startAt,
                50,
                startAt.minusDays(7),
                4,
                ClassSessionStatus.SCHEDULED
        );
        ReflectionTestUtils.setField(classSession, "id", classSessionId);
        return classSession;
    }
}
