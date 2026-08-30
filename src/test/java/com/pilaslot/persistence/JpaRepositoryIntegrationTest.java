package com.pilaslot.persistence;

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
import com.pilaslot.reservation.domain.Reservation;
import com.pilaslot.reservation.domain.ReservationStatus;
import com.pilaslot.reservation.repository.ReservationRepository;
import com.pilaslot.support.PostgreSqlTestContainerConfiguration;
import com.pilaslot.support.PersistentPassFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@Transactional
@ActiveProfiles("test")
@SpringBootTest
@Import(PostgreSqlTestContainerConfiguration.class)
class JpaRepositoryIntegrationTest {

    private static final LocalDateTime CLASS_START_AT = LocalDateTime.of(2026, 8, 22, 13, 0);
    private static final Instant CREATED_INSTANT = Instant.parse("2026-08-16T00:00:00Z");
    private static final Instant UPDATED_INSTANT = Instant.parse("2026-08-16T01:00:00Z");

    @MockitoBean
    private Clock clock;

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
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpClock() {
        given(clock.instant()).willReturn(CREATED_INSTANT);
        given(clock.getZone()).willReturn(ZoneOffset.UTC);
    }

    @Test
    void savesAndLoadsAllEntities() {
        TestFixture fixture = persistFixture("happy-path");
        LocalDateTime reservedAt = CLASS_START_AT.minusDays(1);

        Reservation saved = reservationRepository.saveAndFlush(Reservation.reserve(
                fixture.member(),
                fixture.classSession(),
                fixture.memberPass(),
                reservedAt
        ));

        entityManager.clear();

        Reservation found = reservationRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(found.getReservedAt()).isEqualTo(reservedAt);
        assertThat(found.getCancelledAt()).isNull();
        assertThat(found.getMember().getMemberNumber()).isEqualTo("member-happy-path");
        assertThat(found.getClassSession().getClassType()).isEqualTo(ClassType.REFORMER);
        assertThat(found.getClassSession().getInstructor().getName()).isEqualTo("Instructor happy-path");
        assertThat(found.getClassSession().getReservedCount()).isZero();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void createsAndUpdatesAuditingTimestampsUsingClock() {
        Instructor instructor = instructorRepository.saveAndFlush(new Instructor(
                "Auditing Instructor",
                null
        ));
        LocalDateTime createdAt = LocalDateTime.ofInstant(CREATED_INSTANT, ZoneOffset.UTC);

        assertThat(instructor.getCreatedAt()).isEqualTo(createdAt);
        assertThat(instructor.getUpdatedAt()).isEqualTo(createdAt);

        given(clock.instant()).willReturn(UPDATED_INSTANT);
        ReflectionTestUtils.setField(instructor, "profileImageUrl", "updated-profile.png");
        instructorRepository.saveAndFlush(instructor);

        assertThat(instructor.getCreatedAt()).isEqualTo(createdAt);
        assertThat(instructor.getUpdatedAt())
                .isEqualTo(LocalDateTime.ofInstant(UPDATED_INSTANT, ZoneOffset.UTC));
    }

    @Test
    void rejectsSecondActiveReservationForSameMemberAndClassSession() {
        TestFixture fixture = persistFixture("duplicate");
        LocalDateTime reservedAt = CLASS_START_AT.minusDays(1);

        reservationRepository.saveAndFlush(Reservation.reserve(
                fixture.member(),
                fixture.classSession(),
                fixture.memberPass(),
                reservedAt
        ));

        assertThatThrownBy(() -> reservationRepository.saveAndFlush(Reservation.reserve(
                fixture.member(),
                fixture.classSession(),
                fixture.memberPass(),
                reservedAt.plusMinutes(1)
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsNewActiveReservationAfterCancelledHistory() {
        TestFixture fixture = persistFixture("rebook");
        LocalDateTime firstReservedAt = CLASS_START_AT.minusDays(2);
        LocalDateTime cancelledAt = firstReservedAt.plusHours(1);

        Reservation cancelledHistory = reservationRepository.saveAndFlush(Reservation.reserve(
                fixture.member(),
                fixture.classSession(),
                fixture.memberPass(),
                firstReservedAt
        ));
        jdbcTemplate.update(
                "UPDATE reservation SET status = ?, cancelled_at = ? WHERE id = ?",
                ReservationStatus.CANCELLED.name(),
                Timestamp.valueOf(cancelledAt),
                cancelledHistory.getId()
        );
        entityManager.clear();

        Reservation newActiveReservation = reservationRepository.saveAndFlush(Reservation.reserve(
                fixture.member(),
                fixture.classSession(),
                fixture.memberPass(),
                CLASS_START_AT.minusDays(1)
        ));

        assertThat(cancelledHistory.getId()).isNotEqualTo(newActiveReservation.getId());
        Reservation foundCancelledHistory = reservationRepository.findById(cancelledHistory.getId()).orElseThrow();
        assertThat(foundCancelledHistory.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(foundCancelledHistory.getCancelledAt()).isEqualTo(cancelledAt);
        assertThat(reservationRepository.findAll()).hasSize(2);
    }

    private TestFixture persistFixture(String suffix) {
        Member member = memberRepository.save(new Member(
                "member-" + suffix,
                "encoded-password",
                "Member " + suffix,
                "010-0000-0000"
        ));
        Instructor instructor = instructorRepository.save(new Instructor(
                "Instructor " + suffix,
                null
        ));
        ClassSession classSession = classSessionRepository.save(new ClassSession(
                instructor,
                ClassType.REFORMER,
                CLASS_START_AT,
                50,
                CLASS_START_AT.minusDays(7),
                4,
                ClassSessionStatus.SCHEDULED
        ));
        MemberPass memberPass = PersistentPassFixtures.issue(
                member,
                classSession.getStartAt().toLocalDate(),
                passProductRepository,
                memberPassRepository,
                memberPassHistoryRepository
        );
        return new TestFixture(member, classSession, memberPass);
    }

    private record TestFixture(Member member, ClassSession classSession, MemberPass memberPass) {
    }
}
