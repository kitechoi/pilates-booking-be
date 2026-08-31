package com.pilaslot.persistence;

import com.pilaslot.support.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test")
@SpringBootTest
@Import(PostgreSqlTestContainerConfiguration.class)
class MemberPassMigrationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void commitsWhenRemainingCountMatchesHistorySum() {
        assertThatCode(() -> transactionTemplate.executeWithoutResult(status -> {
            Long memberPassId = insertMemberPass(10);
            insertIssuedHistory(memberPassId, 10);
        })).doesNotThrowAnyException();
    }

    @Test
    void rejectsCommitWhenRemainingCountDiffersFromHistorySum() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            Long memberPassId = insertMemberPass(10);
            insertIssuedHistory(memberPassId, 9);
        })).rootCause()
                .hasMessageContaining("member_pass balance does not match history sum");
    }

    @Test
    void rejectsHistoryUpdate() {
        Fixture fixture = insertValidFixture();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "UPDATE member_pass_history SET memo = ? WHERE id = ?",
                        "변경 시도",
                        fixture.historyId()
                )
        )).rootCause()
                .hasMessageContaining("member_pass_history is append-only");
    }

    @Test
    void rejectsHistoryDelete() {
        Fixture fixture = insertValidFixture();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "DELETE FROM member_pass_history WHERE id = ?",
                        fixture.historyId()
                )
        )).rootCause()
                .hasMessageContaining("member_pass_history is append-only");
    }

    @Test
    void appliesReservationContractConstraints() {
        String memberPassNullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'reservation'
                  AND column_name = 'member_pass_id'
                """, String.class);
        String ownershipForeignKey = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'fk_reservation_member_pass_member'
                """, String.class);
        String cancellationSourceCheck = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'ck_reservation_cancellation_source'
                """, String.class);
        Integer expandForeignKeyCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint
                WHERE conname = 'fk_reservation_member_pass_expand'
                """, Integer.class);

        assertThat(memberPassNullable).isEqualTo("NO");
        assertThat(ownershipForeignKey)
                .contains("FOREIGN KEY (member_pass_id, member_id)")
                .contains("REFERENCES member_pass(id, member_id)");
        assertThat(cancellationSourceCheck)
                .contains("status")
                .contains("cancellation_source")
                .contains("RESERVED")
                .contains("CANCELLED")
                .contains("MEMBER")
                .contains("ADMIN")
                .contains("CLASS_SESSION");
        assertThat(expandForeignKeyCount).isZero();
    }

    @Test
    void rejectsSecondReservationDebitForSameReservation() {
        ReservationFixture fixture = insertReservationFixture(10);
        insertHistory(fixture.memberPassId(), fixture.reservationId(), "RESERVATION_DEBIT", -1, 9);

        assertThatThrownBy(() -> insertHistory(fixture.memberPassId(), fixture.reservationId(), "RESERVATION_DEBIT", -1, 8))
                .hasMessageContaining("uk_member_pass_history_reservation_debit");
    }

    @Test
    void rejectsSecondCancellationRefundForSameReservation() {
        ReservationFixture fixture = insertReservationFixture(10);
        insertHistory(fixture.memberPassId(), fixture.reservationId(), "RESERVATION_DEBIT", -1, 9);
        insertHistory(fixture.memberPassId(), fixture.reservationId(), "CANCELLATION_REFUND", 1, 10);

        assertThatThrownBy(() -> insertHistory(fixture.memberPassId(), fixture.reservationId(), "CANCELLATION_REFUND", 1, 11))
                .hasMessageContaining("uk_member_pass_history_cancellation_refund");
    }

    private Fixture insertValidFixture() {
        return transactionTemplate.execute(status -> {
            Long memberPassId = insertMemberPass(10);
            Long historyId = insertIssuedHistory(memberPassId, 10);
            return new Fixture(memberPassId, historyId);
        });
    }

    private ReservationFixture insertReservationFixture(int initialCount) {
        Long memberPassId = transactionTemplate.execute(status -> {
            Long id = insertMemberPass(initialCount);
            insertIssuedHistory(id, initialCount);
            return id;
        });
        String suffix = UUID.randomUUID().toString();
        Long instructorId = jdbcTemplate.queryForObject("""
                INSERT INTO instructor (name, created_at, updated_at)
                VALUES ('강사', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class);
        Long classSessionId = jdbcTemplate.queryForObject("""
                INSERT INTO class_session (
                    instructor_id, class_type, start_at, duration_minutes,
                    reservation_open_at, capacity, reserved_count, status,
                    created_at, updated_at
                ) VALUES (
                    ?, 'REFORMER', CURRENT_TIMESTAMP, 50,
                    CURRENT_TIMESTAMP, 4, 1, 'SCHEDULED',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                RETURNING id
                """, Long.class, instructorId);
        Long memberId = jdbcTemplate.queryForObject(
                "SELECT member_id FROM member_pass WHERE id = ?", Long.class, memberPassId);
        Long reservationId = jdbcTemplate.queryForObject("""
                INSERT INTO reservation (
                    member_id, class_session_id, member_pass_id, status,
                    reserved_at, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, 'RESERVED',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                RETURNING id
                """, Long.class, memberId, classSessionId, memberPassId);
        return new ReservationFixture(memberPassId, reservationId);
    }

    private void insertHistory(Long memberPassId, Long reservationId, String type, int countDelta, int remainingCountAfter) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    INSERT INTO member_pass_history (
                        member_pass_id, reservation_id, type, count_delta,
                        remaining_count_after, actor_type, memo, created_at, updated_at
                    ) VALUES (
                        ?, ?, ?, ?, ?, 'MEMBER', NULL,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    )
                    """, memberPassId, reservationId, type, countDelta, remainingCountAfter);
            jdbcTemplate.update(
                    "UPDATE member_pass SET remaining_count = ? WHERE id = ?",
                    remainingCountAfter, memberPassId);
        });
    }

    private Long insertMemberPass(int remainingCount) {
        String suffix = UUID.randomUUID().toString();
        Long memberId = jdbcTemplate.queryForObject("""
                INSERT INTO member (
                    member_number, password, name, phone_number, created_at, updated_at
                ) VALUES (?, 'password', '회원', '010-0000-0000', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, "member-" + suffix);
        Long passProductId = jdbcTemplate.queryForObject("""
                INSERT INTO pass_product (
                    code, name, default_price, default_count, default_validity_days,
                    status, created_at, updated_at
                ) VALUES (?, '10회권', 100000, 10, 30, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, "PASS-" + suffix);
        return jdbcTemplate.queryForObject("""
                INSERT INTO member_pass (
                    member_id, pass_product_id, product_name_snapshot, price_paid,
                    initial_count, remaining_count, valid_from, expires_on, status,
                    created_at, updated_at
                ) VALUES (
                    ?, ?, '10회권', 100000,
                    10, ?, CURRENT_DATE, CURRENT_DATE + 30, 'ACTIVE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                RETURNING id
                """, Long.class, memberId, passProductId, remainingCount);
    }

    private Long insertIssuedHistory(Long memberPassId, int countDelta) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO member_pass_history (
                    member_pass_id, reservation_id, type, count_delta,
                    remaining_count_after, actor_type, memo, created_at, updated_at
                ) VALUES (
                    ?, NULL, 'ISSUED', ?, ?, 'SYSTEM', NULL,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                RETURNING id
                """, Long.class, memberPassId, countDelta, countDelta);
    }

    private record Fixture(Long memberPassId, Long historyId) {
    }

    private record ReservationFixture(Long memberPassId, Long reservationId) {
    }
}
