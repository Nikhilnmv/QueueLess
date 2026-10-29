package com.queueless.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queueless.support.IntegrationTestBase;
import com.queueless.support.TestData.TestOrg;
import com.queueless.support.TestData.TestUser;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * The database is the last line of defence: these tests bypass the application and write rows
 * directly, proving that PostgreSQL itself rejects states the services are designed to prevent.
 */
class MigrationAndConstraintsIT extends IntegrationTestBase {

    private UUID queueId;
    private UUID otherQueueId;
    private TestUser user;
    private final LocalDate day = LocalDate.of(2026, 3, 10);
    private final Timestamp now = Timestamp.from(java.time.Instant.parse("2026-03-10T04:30:00Z"));

    @BeforeEach
    void setUp() {
        TestOrg organization = data.organization("constraints");
        queueId = data.queue(organization);
        otherQueueId = data.queue(organization);
        user = data.user("constraint");
    }

    /** Inserts a row whose lifecycle timestamps are consistent with its status. */
    private void insertTicket(int token, String status, String priority) {
        boolean atCounter = status.equals("CALLED") || status.equals("SERVING");
        jdbc.update("""
                insert into queue_tickets (queue_id, guest_name, source, service_date, token_number, status, priority,
                    created_at, checked_in_at, called_at, service_started_at, updated_at)
                values (?, 'Guest', 'GUEST_WALK_IN', ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                queueId, Date.valueOf(day), token, status, priority, now, now,
                atCounter ? now : null, status.equals("SERVING") ? now : null, now);
    }

    private UUID insertAppointment(UUID queue, String reference) {
        return jdbc.queryForObject("""
                insert into appointments (queue_id, user_id, scheduled_at, service_date, booking_reference, status,
                    created_at, updated_at)
                values (?, ?, ?, ?, ?, 'BOOKED', ?, ?) returning id""", UUID.class,
                queue, user.id(), now, Date.valueOf(day), reference, now, now);
    }

    @Test
    void allMigrationsAreAppliedInOrder() {
        List<Map<String, Object>> history = jdbc.queryForList(
                "select version, success from flyway_schema_history where version is not null order by installed_rank");
        assertThat(history).extracting(row -> row.get("version")).containsExactly("1", "2", "3", "4");
        assertThat(history).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(true));
    }

    @Test
    void tokenNumbersAreUniquePerQueueAndDay() {
        insertTicket(1, "WAITING", "NORMAL");
        assertThatThrownBy(() -> insertTicket(1, "WAITING", "NORMAL"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_tickets_token");
    }

    @Test
    void onlyOneTicketPerQueueAndDayCanBeAtTheCounter() {
        insertTicket(1, "CALLED", "NORMAL");
        assertThatThrownBy(() -> insertTicket(2, "SERVING", "NORMAL"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("ux_tickets_active_per_queue_day");
        insertTicket(3, "WAITING", "NORMAL"); // waiting tickets are unaffected
    }

    @Test
    void statusTimestampsAndValuesAreChecked() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into queue_tickets (queue_id, guest_name, source, service_date, token_number, status, created_at, updated_at)
                values (?, 'Guest', 'GUEST_WALK_IN', ?, 9, 'WAITING', ?, ?)""", queueId, Date.valueOf(day), now, now))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_tickets_in_line_checked_in");
        assertThatThrownBy(() -> insertTicket(10, "TELEPORTED", "NORMAL"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_tickets_status");
        assertThatThrownBy(() -> insertTicket(11, "WAITING", "URGENT"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_tickets_priority");
    }

    @Test
    void priorityRankIsGeneratedByTheDatabase() {
        insertTicket(1, "WAITING", "LOW");
        insertTicket(2, "WAITING", "HIGH");
        insertTicket(3, "WAITING", "NORMAL");
        List<Integer> ranks = jdbc.queryForList(
                "select priority_rank from queue_tickets where queue_id = ? order by token_number", Integer.class, queueId);
        assertThat(ranks).containsExactly(3, 1, 2);
    }

    @Test
    void anAppointmentHasAtMostOneTicket() {
        UUID appointmentId = insertAppointment(queueId, "QL-ONE001");
        String insertTicket = """
                insert into queue_tickets (queue_id, user_id, appointment_id, source, service_date, token_number, status,
                    created_at, updated_at)
                values (?, ?, ?, 'APPOINTMENT', ?, ?, 'BOOKED', ?, ?)""";
        jdbc.update(insertTicket, queueId, user.id(), appointmentId, Date.valueOf(day), 20, now, now);

        assertThatThrownBy(() -> jdbc.update(insertTicket, queueId, user.id(), appointmentId,
                Date.valueOf(day.plusDays(1)), 21, now, now))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_tickets_appointment");
    }

    @Test
    void aTicketCannotPointAtAnAppointmentOfAnotherQueue() {
        UUID appointmentId = insertAppointment(queueId, "QL-TWO002");
        assertThatThrownBy(() -> jdbc.update("""
                insert into queue_tickets (queue_id, user_id, appointment_id, source, service_date, token_number, status,
                    created_at, updated_at)
                values (?, ?, ?, 'APPOINTMENT', ?, 1, 'BOOKED', ?, ?)""",
                otherQueueId, user.id(), appointmentId, Date.valueOf(day), now, now))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_tickets_appointment_same_queue");
    }

    @Test
    void emailsMustBeStoredNormalized() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into users (email, full_name, password_hash, created_at, updated_at)
                values ('Mixed.Case@Test.Example', 'X', 'x', ?, ?)""", now, now))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_email_normalized");
    }

    @Test
    void oneActiveBookingPerCustomerPerQueuePerDay() {
        String insert = """
                insert into appointments (queue_id, user_id, scheduled_at, service_date, booking_reference, status,
                    cancelled_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)""";
        jdbc.update(insert, queueId, user.id(), now, Date.valueOf(day), "QL-AAAAA1", "CANCELLED", now, now, now);
        jdbc.update(insert, queueId, user.id(), now, Date.valueOf(day), "QL-AAAAA2", "BOOKED", null, now, now);
        assertThatThrownBy(() -> jdbc.update(insert, queueId, user.id(), now, Date.valueOf(day), "QL-AAAAA3", "BOOKED", null, now, now))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("ux_appointments_active_per_user_day");
    }
}
