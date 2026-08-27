package com.pilaslot.reservation.repository;

import com.pilaslot.classsession.domain.ClassSession;
import com.pilaslot.classsession.domain.ClassSessionStatus;
import com.pilaslot.classsession.domain.ClassType;
import com.pilaslot.classsession.repository.ClassSessionRepository;
import com.pilaslot.instructor.domain.Instructor;
import com.pilaslot.instructor.repository.InstructorRepository;
import com.pilaslot.member.domain.Member;
import com.pilaslot.member.repository.MemberRepository;
import com.pilaslot.pass.domain.MemberPass;
import com.pilaslot.pass.repository.MemberPassHistoryRepository;
import com.pilaslot.pass.repository.MemberPassRepository;
import com.pilaslot.pass.repository.PassProductRepository;
import com.pilaslot.reservation.domain.CancellationSource;
import com.pilaslot.reservation.domain.Reservation;
import com.pilaslot.reservation.domain.ReservationStatus;
import com.pilaslot.support.PostgreSqlTestContainerConfiguration;
import com.pilaslot.support.PersistentPassFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@ActiveProfiles("test")
@SpringBootTest
@Import(PostgreSqlTestContainerConfiguration.class)
class ReservationRepositoryIntegrationTest {

    private static final LocalDateTime WEEK_START = LocalDateTime.of(2026, 8, 17, 0, 0);
    private static final LocalDateTime WEEK_END = LocalDateTime.of(2026, 8, 24, 0, 0);

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private InstructorRepository instructorRepository;

    @Autowired
    private ClassSessionRepository classSessionRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private PassProductRepository passProductRepository;

    @Autowired
    private MemberPassRepository memberPassRepository;

    @Autowired
    private MemberPassHistoryRepository memberPassHistoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void countsOnlyMembersActiveReservationsInsideClassSessionWeek() {
        Member targetMember = saveMember("target");
        Member otherMember = saveMember("other");
        Instructor instructor = instructorRepository.save(new Instructor("김필라", null));

        saveReservation(targetMember, saveClassSession(instructor, WEEK_START));
        saveReservation(
                targetMember,
                saveClassSession(instructor, WEEK_END.minusSeconds(1))
        );
        saveReservation(targetMember, saveClassSession(instructor, WEEK_END));
        Reservation cancelledReservation = saveReservation(
                targetMember,
                saveClassSession(instructor, WEEK_START.plusDays(2))
        );
        saveReservation(otherMember, saveClassSession(instructor, WEEK_START.plusDays(3)));
        reservationRepository.flush();
        jdbcTemplate.update(
                "UPDATE reservation SET status = ?, cancelled_at = ? WHERE id = ?",
                ReservationStatus.CANCELLED.name(),
                WEEK_START.plusDays(2).plusHours(1),
                cancelledReservation.getId()
        );
        entityManager.clear();

        long count = reservationRepository.countByMemberAndStatusInClassSessionWeek(
                targetMember.getId(),
                ReservationStatus.RESERVED,
                WEEK_START,
                WEEK_END
        );

        assertThat(count).isEqualTo(2);
    }

    @Test
    void findsReservationOnlyForItsOwner() {
        Member owner = saveMember("lookup-owner");
        Member otherMember = saveMember("lookup-other");
        Instructor instructor = instructorRepository.save(new Instructor("이필라", null));
        Reservation reservation = saveReservation(
                owner,
                saveClassSession(instructor, WEEK_START.plusDays(1))
        );
        reservationRepository.flush();
        entityManager.clear();

        assertThat(reservationRepository.findByIdAndMemberId(
                reservation.getId(),
                owner.getId()
        )).isPresent();
        assertThat(reservationRepository.findByIdAndMemberId(
                reservation.getId(),
                otherMember.getId()
        )).isEmpty();
    }

    @Test
    void findsMembersReservationHistoryWithWeekStatusAndStableOrdering() {
        Member targetMember = saveMember("history-target");
        Member otherMember = saveMember("history-other");
        Instructor instructor = instructorRepository.save(new Instructor(
                "김라라",
                "https://example.com/instructors/kim.jpg"
        ));
        ClassSession previousWeek = saveClassSession(
                instructor,
                WEEK_START.minusSeconds(1)
        );
        ClassSession weekBoundary = saveClassSession(instructor, WEEK_START);
        ClassSession rebookedClassSession = saveClassSession(
                instructor,
                WEEK_START.plusDays(2).plusHours(19)
        );
        ClassSession weekEndBoundary = saveClassSession(
                instructor,
                WEEK_END.minusSeconds(1)
        );
        ClassSession nextWeek = saveClassSession(instructor, WEEK_END);

        saveReservation(targetMember, previousWeek);
        Reservation first = saveReservation(targetMember, weekBoundary);
        Reservation cancelled = saveCancelledReservation(targetMember, rebookedClassSession);
        Reservation rebooked = saveReservation(targetMember, rebookedClassSession);
        Reservation last = saveCancelledReservation(targetMember, weekEndBoundary);
        saveReservation(targetMember, nextWeek);
        saveReservation(otherMember, rebookedClassSession);
        reservationRepository.flush();
        entityManager.clear();

        List<Reservation> all = reservationRepository
                .findAllWithClassSessionAndInstructorByMemberIdAndClassSessionWeek(
                        targetMember.getId(),
                        WEEK_START,
                        WEEK_END
                );
        List<Reservation> reserved = reservationRepository
                .findAllWithClassSessionAndInstructorByMemberIdAndStatusAndClassSessionWeek(
                        targetMember.getId(),
                        ReservationStatus.RESERVED,
                        WEEK_START,
                        WEEK_END
                );
        List<Reservation> cancelledOnly = reservationRepository
                .findAllWithClassSessionAndInstructorByMemberIdAndStatusAndClassSessionWeek(
                        targetMember.getId(),
                        ReservationStatus.CANCELLED,
                        WEEK_START,
                        WEEK_END
                );

        assertThat(all)
                .extracting(Reservation::getId)
                .containsExactly(
                        first.getId(),
                        cancelled.getId(),
                        rebooked.getId(),
                        last.getId()
                );
        assertThat(all)
                .extracting(Reservation::getStatus)
                .containsExactly(
                        ReservationStatus.RESERVED,
                        ReservationStatus.CANCELLED,
                        ReservationStatus.RESERVED,
                        ReservationStatus.CANCELLED
                );
        assertThat(all).allSatisfy(reservation -> {
            assertThat(reservation.getClassSession().getStartAt())
                    .isAfterOrEqualTo(WEEK_START)
                    .isBefore(WEEK_END);
            assertThat(reservation.getClassSession().getInstructor().getName())
                    .isEqualTo("김라라");
        });
        assertThat(reserved)
                .extracting(Reservation::getId)
                .containsExactly(first.getId(), rebooked.getId());
        assertThat(cancelledOnly)
                .extracting(Reservation::getId)
                .containsExactly(cancelled.getId(), last.getId());
        assertThat(cancelled.getClassSession().getId())
                .isEqualTo(rebooked.getClassSession().getId());
    }

    @Test
    void countsOnlyMembersCancelledReservationsInsideClassSessionWeek() {
        Member targetMember = saveMember("cancel-target");
        Member otherMember = saveMember("cancel-other");
        Instructor instructor = instructorRepository.save(new Instructor("박필라", null));

        saveCancelledReservation(targetMember, saveClassSession(instructor, WEEK_START));
        saveCancelledReservation(
                targetMember,
                saveClassSession(instructor, WEEK_END.minusSeconds(1))
        );
        saveCancelledReservation(targetMember, saveClassSession(instructor, WEEK_END));
        saveReservation(targetMember, saveClassSession(instructor, WEEK_START.plusDays(2)));
        saveCancelledReservation(
                otherMember,
                saveClassSession(instructor, WEEK_START.plusDays(3))
        );
        reservationRepository.flush();
        entityManager.clear();

        long count = reservationRepository.countByMemberAndStatusInClassSessionWeek(
                targetMember.getId(),
                ReservationStatus.CANCELLED,
                WEEK_START,
                WEEK_END
        );

        assertThat(count).isEqualTo(2);
    }

    private Member saveMember(String suffix) {
        return memberRepository.save(new Member(
                "weekly-" + suffix,
                "encoded-password",
                "Member " + suffix,
                "010-0000-0000"
        ));
    }

    private ClassSession saveClassSession(Instructor instructor, LocalDateTime startAt) {
        return classSessionRepository.save(new ClassSession(
                instructor,
                ClassType.REFORMER,
                startAt,
                50,
                startAt.minusDays(7),
                20,
                ClassSessionStatus.SCHEDULED
        ));
    }

    private Reservation saveReservation(Member member, ClassSession classSession) {
        MemberPass memberPass = PersistentPassFixtures.issue(
                member,
                classSession.getStartAt().toLocalDate(),
                passProductRepository,
                memberPassRepository,
                memberPassHistoryRepository
        );
        return reservationRepository.save(Reservation.reserve(
                member,
                classSession,
                memberPass,
                classSession.getStartAt().minusDays(1)
        ));
    }

    private Reservation saveCancelledReservation(Member member, ClassSession classSession) {
        MemberPass memberPass = PersistentPassFixtures.issue(
                member,
                classSession.getStartAt().toLocalDate(),
                passProductRepository,
                memberPassRepository,
                memberPassHistoryRepository
        );
        Reservation reservation = Reservation.reserve(
                member,
                classSession,
                memberPass,
                classSession.getStartAt().minusDays(1)
        );
        reservation.cancel(classSession.getStartAt().minusHours(10), CancellationSource.MEMBER);
        return reservationRepository.save(reservation);
    }
}
