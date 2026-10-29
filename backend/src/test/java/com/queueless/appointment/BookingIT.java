package com.queueless.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.queueless.membership.domain.MembershipRole;
import com.queueless.queue.domain.QueueSettings;
import com.queueless.support.ApiClient;
import com.queueless.support.ApiClient.ApiResponse;
import com.queueless.support.IntegrationTestBase;
import com.queueless.support.TestData.TestOrg;
import com.queueless.support.TestData.TestUser;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BookingIT extends IntegrationTestBase {

    private TestOrg organization;
    private UUID queueId;
    private TestUser staff;
    private TestUser customer;
    private LocalDate tomorrow;

    @BeforeEach
    void setUp() {
        organization = data.organization("booking");
        queueId = data.queue(organization, 2);
        staff = data.member(organization, MembershipRole.STAFF);
        customer = data.user("customer");
        tomorrow = today().plusDays(1);
    }

    private ApiResponse book(ApiClient client, Instant slot) {
        return client.post("/api/queues/" + queueId + "/appointments", Map.of("scheduledAt", slot.toString()));
    }

    private JsonNode slot(LocalDate date, int hour, int minute) {
        JsonNode slots = anonymous().get("/api/queues/" + queueId + "/slots?date=" + date).expect(200).body().path("slots");
        Instant wanted = at(date, hour, minute);
        return StreamSupport.stream(slots.spliterator(), false)
                .filter(s -> Instant.parse(s.path("startsAt").asText()).equals(wanted))
                .findFirst().orElseThrow();
    }

    @Test
    void listsTheSlotGridInTheOrganizationsTimeZone() {
        JsonNode response = anonymous().get("/api/queues/" + queueId + "/slots?date=" + tomorrow).expect(200).body();
        JsonNode slots = response.path("slots");
        assertThat(slots).hasSize(32); // 09:00-17:00 in 15-minute slots
        assertThat(slots.get(0).path("localTime").asText()).startsWith("09:00");
        assertThat(slots.get(31).path("localTime").asText()).startsWith("16:45");
        assertThat(slots.get(0).path("available").asInt()).isEqualTo(2);
        assertThat(response.path("timezone").asText()).isEqualTo("Asia/Kolkata");

        // Today: slots before 10:00 local time have already started and cannot be booked.
        JsonNode todaySlots = anonymous().get("/api/queues/" + queueId + "/slots").expect(200).body().path("slots");
        assertThat(todaySlots.get(0).path("bookable").asBoolean()).isFalse(); // 09:00
        assertThat(todaySlots.get(5).path("bookable").asBoolean()).isTrue();  // 10:15
    }

    @Test
    void bookingReservesAUniqueTokenAndConsumesCapacity() {
        ApiResponse booking = book(as(customer), at(tomorrow, 10, 30)).expect(201);

        assertThat(booking.text("status")).isEqualTo("BOOKED");
        assertThat(booking.text("bookingReference")).matches("QL-[0-9A-HJKMNP-TV-Z]{6}");
        assertThat(booking.body().path("tokenNumber").asInt()).isEqualTo(1);
        assertThat(booking.text("ticketStatus")).isEqualTo("BOOKED");
        assertThat(booking.text("serviceDate")).isEqualTo(tomorrow.toString());
        assertThat(slot(tomorrow, 10, 30).path("available").asInt()).isEqualTo(1);

        TestUser second = data.user("second");
        assertThat(book(as(second), at(tomorrow, 11, 0)).expect(201).body().path("tokenNumber").asInt()).isEqualTo(2);

        Integer confirmations = jdbc.queryForObject("select count(*) from notifications where user_id = ? and type = 'APPOINTMENT_CONFIRMED'",
                Integer.class, customer.id());
        assertThat(confirmations).isEqualTo(1);
    }

    @Test
    void aFullSlotIsRejectedWithConflict() {
        book(as(customer), at(tomorrow, 9, 0)).expect(201);
        book(as(data.user("b")), at(tomorrow, 9, 0)).expect(201);

        ApiResponse third = book(as(data.user("c")), at(tomorrow, 9, 0)).expect(409);
        assertThat(third.code()).isEqualTo("SLOT_FULL");
        assertThat(slot(tomorrow, 9, 0).path("bookable").asBoolean()).isFalse();
    }

    @Test
    void cancellationReleasesCapacityAndIsIdempotent() {
        ApiClient client = as(customer);
        String appointmentId = book(client, at(tomorrow, 9, 15)).expect(201).text("id");
        book(as(data.user("b")), at(tomorrow, 9, 15)).expect(201);
        assertThat(slot(tomorrow, 9, 15).path("available").asInt()).isZero();

        ApiResponse cancelled = client.post("/api/appointments/" + appointmentId + "/cancel").expect(200);
        assertThat(cancelled.text("status")).isEqualTo("CANCELLED");
        assertThat(cancelled.text("ticketStatus")).isEqualTo("CANCELLED");
        assertThat(slot(tomorrow, 9, 15).path("available").asInt()).isEqualTo(1);

        // Repeating the request returns the same final state instead of an error.
        assertThat(client.post("/api/appointments/" + appointmentId + "/cancel").expect(200).text("status"))
                .isEqualTo("CANCELLED");
        // The released place can be booked by someone else.
        book(as(data.user("c")), at(tomorrow, 9, 15)).expect(201);
        // The cancelled ticket can never re-enter the queue.
        client.post("/api/appointments/" + appointmentId + "/check-in").expect(409);
    }

    @Test
    void duplicateSubmissionsWithTheSameIdempotencyKeyCreateOneBooking() {
        ApiClient client = as(customer);
        String key = UUID.randomUUID().toString();
        Map<String, String> headers = Map.of("Idempotency-Key", key);
        Map<String, String> body = Map.of("scheduledAt", at(tomorrow, 12, 0).toString());

        ApiResponse first = client.post("/api/queues/" + queueId + "/appointments", body, headers).expect(201);
        ApiResponse replay = client.post("/api/queues/" + queueId + "/appointments", body, headers).expect(200);

        assertThat(replay.text("id")).isEqualTo(first.text("id"));
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        Integer rows = jdbc.queryForObject("select count(*) from appointments where user_id = ?", Integer.class, customer.id());
        assertThat(rows).isEqualTo(1);

        // Reusing the key for a different slot is a client error.
        ApiResponse reused = client.post("/api/queues/" + queueId + "/appointments",
                Map.of("scheduledAt", at(tomorrow, 12, 15).toString()), headers).expect(422);
        assertThat(reused.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void oneActiveBookingPerCustomerPerQueuePerDay() {
        ApiClient client = as(customer);
        book(client, at(tomorrow, 13, 0)).expect(201);
        assertThat(book(client, at(tomorrow, 14, 0)).expect(409).code()).isEqualTo("ALREADY_BOOKED");
        book(client, at(tomorrow.plusDays(1), 14, 0)).expect(201); // another day is fine
    }

    @Test
    void invalidSlotsAreRejected() {
        ApiClient client = as(customer);
        assertThat(book(client, at(tomorrow, 10, 7)).expect(400).code()).isEqualTo("INVALID_SLOT");      // off-grid
        assertThat(book(client, at(tomorrow, 8, 45)).expect(400).code()).isEqualTo("INVALID_SLOT");      // before opening
        assertThat(book(client, at(tomorrow, 17, 0)).expect(400).code()).isEqualTo("INVALID_SLOT");      // ends after closing
        assertThat(book(client, at(today(), 9, 30)).expect(400).code()).isEqualTo("INVALID_SLOT");       // already started
        assertThat(book(client, at(today().plusDays(15), 10, 0)).expect(400).code()).isEqualTo("INVALID_SLOT"); // horizon
        client.post("/api/queues/" + queueId + "/appointments", Map.of()).expect(400);
        client.post("/api/queues/" + queueId + "/appointments", "{not json").expect(400);
    }

    @Test
    void bookingCanBeDisabledByTheAdministrator() {
        UUID closedForBooking = data.queue(organization, new QueueSettings("No bookings", null, "N",
                LocalTime.of(9, 0), LocalTime.of(17, 0), 15, 2, 10, false));
        ApiResponse response = as(customer).post("/api/queues/" + closedForBooking + "/appointments",
                Map.of("scheduledAt", at(tomorrow, 10, 0).toString())).expect(409);
        assertThat(response.code()).isEqualTo("BOOKING_DISABLED");
    }

    @Test
    void checkInOpensBeforeTheAppointmentAndPutsTheTicketInLine() {
        ApiClient client = as(customer);
        String appointmentId = book(client, at(today(), 12, 0)).expect(201).text("id");

        ApiResponse early = client.post("/api/appointments/" + appointmentId + "/check-in").expect(409);
        assertThat(early.code()).isEqualTo("CHECK_IN_NOT_ALLOWED");

        clock.advance(Duration.ofMinutes(75)); // 11:15, inside the 60-minute early window
        ApiResponse ticket = client.post("/api/appointments/" + appointmentId + "/check-in").expect(200);
        assertThat(ticket.text("status")).isEqualTo("WAITING");
        assertThat(ticket.body().path("live").path("position").asInt()).isEqualTo(1);
        assertThat(client.get("/api/appointments/" + appointmentId).expect(200).text("status")).isEqualTo("CHECKED_IN");

        // Repeated check-in is harmless.
        assertThat(client.post("/api/appointments/" + appointmentId + "/check-in").expect(200).text("status"))
                .isEqualTo("WAITING");
    }

    @Test
    void staffCanCheckInAndCancelOnBehalfOfCustomers() {
        String first = book(as(customer), at(today(), 10, 30)).expect(201).text("id");
        TestUser other = data.user("other");
        String second = book(as(other), at(today(), 10, 45)).expect(201).text("id");
        ApiClient desk = as(staff);

        assertThat(desk.post("/api/appointments/" + first + "/check-in").expect(200).text("status")).isEqualTo("WAITING");
        assertThat(desk.post("/api/appointments/" + second + "/cancel").expect(200).text("status")).isEqualTo("CANCELLED");

        Integer notified = jdbc.queryForObject("select count(*) from notifications where user_id = ? and type = 'APPOINTMENT_CANCELLED'",
                Integer.class, other.id());
        assertThat(notified).isEqualTo(1);
    }

    @Test
    void anAppointmentAtTheCounterCanNoLongerBeCancelled() {
        ApiClient client = as(customer);
        String appointmentId = book(client, at(today(), 10, 30)).expect(201).text("id");
        client.post("/api/appointments/" + appointmentId + "/check-in").expect(200);
        as(staff).post("/api/staff/queues/" + queueId + "/call-next").expect(200);

        ApiResponse response = client.post("/api/appointments/" + appointmentId + "/cancel").expect(409);
        assertThat(response.code()).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(client.get("/api/appointments/" + appointmentId).expect(200).text("ticketStatus")).isEqualTo("CALLED");
    }

    @Test
    void listsMyAppointmentsWithActionFlags() {
        ApiClient client = as(customer);
        book(client, at(tomorrow, 15, 0)).expect(201);
        JsonNode mine = client.get("/api/me/appointments").expect(200).body();
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).path("canCancel").asBoolean()).isTrue();
        assertThat(mine.get(0).path("canCheckIn").asBoolean()).isFalse(); // tomorrow
        assertThat(mine.get(0).path("displayToken").asText()).isEqualTo("T-001");
    }
}
