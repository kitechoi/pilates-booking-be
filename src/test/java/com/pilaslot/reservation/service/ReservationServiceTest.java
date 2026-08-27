package com.pilaslot.reservation.service;

import com.pilaslot.classsession.domain.ClassSession;
import com.pilaslot.classsession.domain.ClassSessionStatus;
import com.pilaslot.classsession.domain.ClassType;
import com.pilaslot.classsession.repository.ClassSessionRepository;
import com.pilaslot.global.exception.BusinessException;
import com.pilaslot.global.exception.ErrorCode;
import com.pilaslot.instructor.domain.Instructor;
import com.pilaslot.member.domain.Member;
import com.pilaslot.member.repository.MemberRepository;
import com.pilaslot.pass.domain.MemberPass;
import com.pilaslot.pass.domain.MemberPassHistory;
import com.pilaslot.pass.repository.MemberPassHistoryRepository;
import com.pilaslot.pass.repository.MemberPassRepository;
import com.pilaslot.reservation.domain.CancellationSource;
import com.pilaslot.reservation.domain.Reservation;
import com.pilaslot.reservation.domain.ReservationStatus;
import com.pilaslot.reservation.dto.response.ReservationCreateResponse;
import com.pilaslot.reservation.repository.ReservationRepository;
import com.pilaslot.support.PassFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long CLASS_SESSION_ID = 10L;
    private static final Long RESERVATION_ID = 55L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 19, 13, 0);
    private static final LocalDateTime DEFAULT_START_AT = LocalDateTime.of(2026, 8, 21, 15, 0);
    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");

    @Mock
    private ClassSessionRepository classSessionRepository;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private MemberPassRepository memberPassRepository;

    @Mock
    private MemberPassHistoryRepository memberPassHistoryRepository;

    private Member member;
    private MemberPass memberPass;
    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZONE_ID).toInstant(), ZONE_ID);
        reservationService = new ReservationService(
                classSessionRepository,
                memberRepository,
                reservationRepository,
                memberPassRepository,
                memberPassHistoryRepository,
                clock
        );
        member = new Member("1234", "encoded-password", "홍길동", "010-1234-5678");
        ReflectionTestUtils.setField(member, "id", MEMBER_ID);
        memberPass = PassFixtures.memberPass(member, DEFAULT_START_AT.toLocalDate());
        lenient().when(memberRepository.findByIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(member));
    }

    @Test
    void createsReservationAndIncreasesReservedCount() {
        ClassSession classSession = classSession(
                DEFAULT_START_AT,
                NOW.minusDays(1),
                4,
                1,
                ClassSessionStatus.SCHEDULED
        );
        prepareSuccessfulReservation(classSession, 0);

        ReservationCreateResponse response = reservationService.reserve(MEMBER_ID, CLASS_SESSION_ID);

        assertThat(response.id()).isEqualTo(55L);
        assertThat(response.classSessionId()).isEqualTo(CLASS_SESSION_ID);
        assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(response.reservedAt()).isEqualTo(NOW);
        assertThat(classSession.getReservedCount()).isEqualTo(2);
        InOrder inOrder = inOrder(
                classSessionRepository,
                reservationRepository,
                memberRepository,
                memberPassRepository
        );
        inOrder.verify(memberRepository).findByIdForUpdate(MEMBER_ID);
        inOrder.verify(classSessionRepository).findByIdForUpdate(CLASS_SESSION_ID);
        inOrder.verify(reservationRepository).existsByMemberIdAndClassSessionIdAndStatus(
                MEMBER_ID,
                CLASS_SESSION_ID,
                ReservationStatus.RESERVED
        );
        inOrder.verify(reservationRepository).countByMemberAndStatusInClassSessionWeek(
                MEMBER_ID,
                ReservationStatus.RESERVED,
                LocalDateTime.of(2026, 8, 17, 0, 0),
                LocalDateTime.of(2026, 8, 24, 0, 0)
        );
        inOrder.verify(memberPassRepository).findUsableForUpdate(any(), any(), any(), any());
        inOrder.verify(reservationRepository).save(any(Reservation.class));
    }

    @Test
    void rejectsUnknownClassSession() {
        given(classSessionRepository.findByIdForUpdate(CLASS_SESSION_ID)).willReturn(Optional.empty());

        assertError(ErrorCode.CLASS_SESSION_NOT_FOUND);
    }

    @Test
    void rejectsCancelledClassSession() {
        given(classSessionRepository.findByIdForUpdate(CLASS_SESSION_ID)).willReturn(Optional.of(classSession(
                DEFAULT_START_AT,
                NOW.minusDays(1),
                4,
                0,
                ClassSessionStatus.CANCELLED
        )));

        assertError(ErrorCode.CLASS_SESSION_CANCELLED);
    }

    @Test
    void rejectsBeforeReservationOpenTime() {
        given(classSessionRepository.findByIdForUpdate(CLASS_SESSION_ID)).willReturn(Optional.of(classSession(
                DEFAULT_START_AT,
                NOW.plusNanos(1),
                4,
                0,
                ClassSessionStatus.SCHEDULED
        )));

        assertError(ErrorCode.RESERVATION_NOT_OPEN);
    }

    @Test
    void allowsExactlyAtReservationOpenTime() {
        ClassSession classSession = classSession(
                DEFAULT_START_AT,
                NOW,
                4,
                0,
                ClassSessionStatus.SCHEDULED
        );
        prepareSuccessfulReservation(classSession, 0);

        ReservationCreateResponse response = reservationService.reserve(MEMBER_ID, CLASS_SESSION_ID);

        assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void rejectsAfterReservationDeadline() {
        given(classSessionRepository.findByIdForUpdate(CLASS_SESSION_ID)).willReturn(Optional.of(classSession(
                NOW.plusHours(2).minusNanos(1),
                NOW.minusDays(1),
                4,
                0,
                ClassSessionStatus.SCHEDULED
        )));

        assertError(ErrorCode.RESERVATION_CLOSED);
    }

    @Test
    void allowsExactlyAtReservationDeadline() {
        ClassSession classSession = classSession(
                NOW.plusHours(2),
                NOW.minusDays(1),
                4,
                0,
                ClassSessionStatus.SCHEDULED
        );
        prepareSuccessfulReservation(classSession, 0);

        ReservationCreateResponse response = reservationService.reserve(MEMBER_ID, CLASS_SESSION_ID);

        assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void rejectsDuplicateActiveReservation() {
        ClassSession classSession = defaultClassSession();
        given(classSessionRepository.findByIdForUpdate(CLASS_SESSION_ID)).willReturn(Optional.of(classSession));
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(member));
        given(reservationRepository.existsByMemberIdAndClassSessionIdAndStatus(
                MEMBER_ID,
                CLASS_SESSION_ID,
                ReservationStatus.RESERVED
        )).willReturn(true);

        assertError(ErrorCode.DUPLICATE_RESERVATION);
    }

    @Test
    void allowsReservationWhenOnlyCancelledHistoryExists() {
        ClassSession classSession = defaultClassSession();
        prepareSuccessfulReservation(classSession, 0);

        ReservationCreateResponse response = reservationService.reserve(MEMBER_ID, CLASS_SESSION_ID);

        assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED);
        verify(reservationRepository).existsByMemberIdAndClassSessionIdAndStatus(
                MEMBER_ID,
                CLASS_SESSION_ID,
                ReservationStatus.RESERVED
        );
    }

    @Test
    void rejectsWhenWeeklyActiveReservationCountIsFourteen() {
        ClassSession classSession = defaultClassSession();
        given(classSessionRepository.findByIdForUpdate(CLASS_SESSION_ID)).willReturn(Optional.of(classSession));
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(member));
        given(reservationRepository.existsByMemberIdAndClassSessionIdAndStatus(
                MEMBER_ID,
                CLASS_SESSION_ID,
                ReservationStatus.RESERVED
        )).willReturn(false);
        given(reservationRepository.countByMemberAndStatusInClassSessionWeek(
                MEMBER_ID,
                ReservationStatus.RESERVED,
                LocalDateTime.of(2026, 8, 17, 0, 0),
                LocalDateTime.of(2026, 8, 24, 0, 0)
        )).willReturn(14L);

        assertError(ErrorCode.WEEKLY_RESERVATION_LIMIT_EXCEEDED);
    }

    @Test
    void allowsWhenWeeklyActiveReservationCountIsThirteen() {
        ClassSession classSession = defaultClassSession();
        prepareSuccessfulReservation(classSession, 13);

        ReservationCreateResponse response = reservationService.reserve(MEMBER_ID, CLASS_SESSION_ID);

        assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void rejectsWhenClassSessionIsFull() {
        ClassSession classSession = classSession(
                DEFAULT_START_AT,
                NOW.minusDays(1),
                4,
                4,
                ClassSessionStatus.SCHEDULED
        );
        given(classSessionRepository.findByIdForUpdate(CLASS_SESSION_ID)).willReturn(Optional.of(classSession));
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(member));
        given(reservationRepository.existsByMemberIdAndClassSessionIdAndStatus(
                MEMBER_ID,
                CLASS_SESSION_ID,
                ReservationStatus.RESERVED
        )).willReturn(false);
        given(reservationRepository.countByMemberAndStatusInClassSessionWeek(
                MEMBER_ID,
                ReservationStatus.RESERVED,
                LocalDateTime.of(2026, 8, 17, 0, 0),
                LocalDateTime.of(2026, 8, 24, 0, 0)
        )).willReturn(0L);

        assertError(ErrorCode.CLASS_SESSION_FULL);
    }

    @Test
    void allowsWhenOneSeatRemains() {
        ClassSession classSession = classSession(
                DEFAULT_START_AT,
                NOW.minusDays(1),
                4,
                3,
                ClassSessionStatus.SCHEDULED
        );
        prepareSuccessfulReservation(classSession, 0);

        reservationService.reserve(MEMBER_ID, CLASS_SESSION_ID);

        assertThat(classSession.getReservedCount()).isEqualTo(4);
    }

    @Test
    void rejectsWhenAuthenticatedMemberNoLongerExists() {
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.empty());

        assertError(ErrorCode.UNAUTHORIZED);
        verify(reservationRepository, never()).existsByMemberIdAndClassSessionIdAndStatus(
                any(),
                any(),
                any()
        );
        verify(reservationRepository, never()).countByMemberAndStatusInClassSessionWeek(
                any(),
                any(),
                any(),
                any()
        );
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void cancelsReservationAndDecreasesReservedCount() {
        LocalDateTime reservedAt = NOW.minusDays(1);
        ClassSession classSession = cancellableClassSession(DEFAULT_START_AT);
        Reservation reservation = reservation(member, classSession, reservedAt);
        prepareSuccessfulCancellation(reservation, 0);

        reservationService.cancel(MEMBER_ID, RESERVATION_ID);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelledAt()).isEqualTo(NOW);
        assertThat(reservation.getReservedAt()).isEqualTo(reservedAt);
        assertThat(classSession.getReservedCount()).isZero();
        verify(reservationRepository, never()).save(any());
        InOrder inOrder = inOrder(reservationRepository, classSessionRepository);
        inOrder.verify(reservationRepository).findClassSessionIdByIdAndMemberId(RESERVATION_ID, MEMBER_ID);
        inOrder.verify(classSessionRepository).findByIdForUpdate(CLASS_SESSION_ID);
        inOrder.verify(reservationRepository).findByIdAndMemberId(RESERVATION_ID, MEMBER_ID);
    }

    @Test
    void rejectsUnknownReservation() {
        given(reservationRepository.findClassSessionIdByIdAndMemberId(RESERVATION_ID, MEMBER_ID))
                .willReturn(Optional.empty());

        assertCancelError(ErrorCode.RESERVATION_NOT_FOUND);
    }

    @Test
    void rejectsReservationOwnedByAnotherMemberAsNotFound() {
        given(reservationRepository.findClassSessionIdByIdAndMemberId(RESERVATION_ID, MEMBER_ID))
                .willReturn(Optional.empty());

        assertCancelError(ErrorCode.RESERVATION_NOT_FOUND);
        verify(reservationRepository, never()).countByMemberAndStatusInClassSessionWeek(
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void rejectsAlreadyCancelledReservation() {
        Reservation reservation = reservation(
                member,
                cancellableClassSession(DEFAULT_START_AT),
                NOW.minusDays(1)
        );
        reservation.cancel(NOW.minusHours(1), CancellationSource.MEMBER);
        given(reservationRepository.findClassSessionIdByIdAndMemberId(RESERVATION_ID, MEMBER_ID))
                .willReturn(Optional.of(CLASS_SESSION_ID));
        given(classSessionRepository.findByIdForUpdate(CLASS_SESSION_ID))
                .willReturn(Optional.of(reservation.getClassSession()));
        given(reservationRepository.findByIdAndMemberId(RESERVATION_ID, MEMBER_ID))
                .willReturn(Optional.of(reservation));

        assertCancelError(ErrorCode.RESERVATION_ALREADY_CANCELLED);
        verify(reservationRepository, never()).countByMemberAndStatusInClassSessionWeek(
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void rejectsCancellationWhenMemberPassIsNotAssigned() {
        Reservation reservation = reservation(
                member,
                cancellableClassSession(DEFAULT_START_AT),
                NOW.minusDays(1)
        );
        ReflectionTestUtils.setField(reservation, "memberPass", null);
        prepareSuccessfulCancellation(reservation, 0);

        assertCancelError(ErrorCode.RESERVATION_MEMBER_PASS_NOT_ASSIGNED);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        verify(memberPassHistoryRepository, never()).save(any());
    }

    @Test
    void rejectsAfterCancellationDeadline() {
        ClassSession classSession = cancellableClassSession(NOW.plusHours(8).minusNanos(1));
        Reservation reservation = reservation(member, classSession, NOW.minusDays(1));
        given(reservationRepository.findClassSessionIdByIdAndMemberId(RESERVATION_ID, MEMBER_ID))
                .willReturn(Optional.of(CLASS_SESSION_ID));
        given(classSessionRepository.findByIdForUpdate(CLASS_SESSION_ID))
                .willReturn(Optional.of(classSession));
        given(reservationRepository.findByIdAndMemberId(RESERVATION_ID, MEMBER_ID))
                .willReturn(Optional.of(reservation));

        assertCancelError(ErrorCode.CANCELLATION_CLOSED);
        verify(reservationRepository, never()).countByMemberAndStatusInClassSessionWeek(
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void allowsExactlyAtCancellationDeadline() {
        Reservation reservation = reservation(
                member,
                cancellableClassSession(NOW.plusHours(8)),
                NOW.minusDays(1)
        );
        prepareSuccessfulCancellation(reservation, 0);

        reservationService.cancel(MEMBER_ID, RESERVATION_ID);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void rejectsWhenWeeklyCancellationCountIsSeven() {
        Reservation reservation = reservation(
                member,
                cancellableClassSession(DEFAULT_START_AT),
                NOW.minusDays(1)
        );
        prepareSuccessfulCancellation(reservation, 7);

        assertCancelError(ErrorCode.WEEKLY_CANCELLATION_LIMIT_EXCEEDED);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(reservation.getClassSession().getReservedCount()).isEqualTo(1);
    }

    @Test
    void allowsWhenWeeklyCancellationCountIsSix() {
        Reservation reservation = reservation(
                member,
                cancellableClassSession(DEFAULT_START_AT),
                NOW.minusDays(1)
        );
        prepareSuccessfulCancellation(reservation, 6);

        reservationService.cancel(MEMBER_ID, RESERVATION_ID);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void countsCancellationsInClassSessionWeek() {
        LocalDateTime classStartAt = LocalDateTime.of(2026, 8, 25, 15, 0);
        Reservation reservation = reservation(
                member,
                cancellableClassSession(classStartAt),
                NOW.minusDays(1)
        );
        prepareSuccessfulCancellation(reservation, 0);

        reservationService.cancel(MEMBER_ID, RESERVATION_ID);

        verify(reservationRepository).countByMemberAndStatusAndCancellationSourceInClassSessionWeek(
                MEMBER_ID,
                ReservationStatus.CANCELLED,
                CancellationSource.MEMBER,
                LocalDateTime.of(2026, 8, 24, 0, 0),
                LocalDateTime.of(2026, 8, 31, 0, 0)
        );
    }

    private void prepareSuccessfulReservation(ClassSession classSession, long weeklyCount) {
        given(classSessionRepository.findByIdForUpdate(CLASS_SESSION_ID)).willReturn(Optional.of(classSession));
        given(reservationRepository.existsByMemberIdAndClassSessionIdAndStatus(
                MEMBER_ID,
                CLASS_SESSION_ID,
                ReservationStatus.RESERVED
        )).willReturn(false);
        LocalDateTime weekStart = classSession.getStartAt().toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay();
        given(reservationRepository.countByMemberAndStatusInClassSessionWeek(
                MEMBER_ID,
                ReservationStatus.RESERVED,
                weekStart,
                weekStart.plusWeeks(1)
        )).willReturn(weeklyCount);
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(member));
        given(reservationRepository.save(any(Reservation.class))).willAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            ReflectionTestUtils.setField(reservation, "id", 55L);
            return reservation;
        });
        given(memberPassRepository.findUsableForUpdate(any(), any(), any(), any()))
                .willReturn(List.of(memberPass));
        given(memberPassHistoryRepository.save(any(MemberPassHistory.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
    }

    private void prepareSuccessfulCancellation(Reservation reservation, long weeklyCount) {
        given(reservationRepository.findClassSessionIdByIdAndMemberId(RESERVATION_ID, MEMBER_ID))
                .willReturn(Optional.of(CLASS_SESSION_ID));
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(member));
        given(classSessionRepository.findByIdForUpdate(CLASS_SESSION_ID))
                .willReturn(Optional.of(reservation.getClassSession()));
        given(reservationRepository.findByIdAndMemberId(RESERVATION_ID, MEMBER_ID))
                .willReturn(Optional.of(reservation));
        LocalDateTime weekStart = reservation.getClassSession().getStartAt().toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay();
        given(reservationRepository.countByMemberAndStatusAndCancellationSourceInClassSessionWeek(
                MEMBER_ID,
                ReservationStatus.CANCELLED,
                CancellationSource.MEMBER,
                weekStart,
                weekStart.plusWeeks(1)
        )).willReturn(weeklyCount);
    }

    private Reservation reservation(
            Member reservationMember,
            ClassSession classSession,
            LocalDateTime reservedAt
    ) {
        MemberPass reservationPass = PassFixtures.memberPass(
                reservationMember,
                classSession.getStartAt().toLocalDate()
        );
        reservationPass.debit();
        Reservation reservation = Reservation.reserve(
                reservationMember,
                classSession,
                reservationPass,
                reservedAt
        );
        ReflectionTestUtils.setField(reservation, "id", RESERVATION_ID);
        return reservation;
    }

    private ClassSession cancellableClassSession(LocalDateTime startAt) {
        return classSession(
                startAt,
                NOW.minusDays(7),
                4,
                1,
                ClassSessionStatus.SCHEDULED
        );
    }

    private ClassSession defaultClassSession() {
        return classSession(
                DEFAULT_START_AT,
                NOW.minusDays(1),
                4,
                0,
                ClassSessionStatus.SCHEDULED
        );
    }

    private ClassSession classSession(
            LocalDateTime startAt,
            LocalDateTime reservationOpenAt,
            int capacity,
            int reservedCount,
            ClassSessionStatus status
    ) {
        ClassSession classSession = new ClassSession(
                new Instructor("김필라", null),
                ClassType.REFORMER,
                startAt,
                50,
                reservationOpenAt,
                capacity,
                status
        );
        ReflectionTestUtils.setField(classSession, "id", CLASS_SESSION_ID);
        ReflectionTestUtils.setField(classSession, "reservedCount", reservedCount);
        return classSession;
    }

    private void assertError(ErrorCode errorCode) {
        assertThatThrownBy(() -> reservationService.reserve(MEMBER_ID, CLASS_SESSION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }

    private void assertCancelError(ErrorCode errorCode) {
        assertThatThrownBy(() -> reservationService.cancel(MEMBER_ID, RESERVATION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }
}
