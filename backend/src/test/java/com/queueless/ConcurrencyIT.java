package com.queueless;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queueless.appointment.api.AppointmentDtos.AppointmentResponse;
import com.queueless.appointment.service.AppointmentService;
import com.queueless.appointment.service.AppointmentService.BookingResult;
import com.queueless.common.error.ApiException;
import com.queueless.common.error.ErrorCode;
import com.queueless.membership.domain.MembershipRole;
import com.queueless.realtime.QueueChangeReason;
import com.queueless.realtime.RecordingRealtimeGateway;
import com.queueless.support.ApiClient;
import com.queueless.support.IntegrationTestBase;
import com.queueless.support.TestData.TestOrg;
import com.queueless.support.TestData.TestUser;
import com.queueless.ticket.api.TicketDtos.BoardTicket;
import com.queueless.ticket.api.TicketDtos.TicketResponse;
import com.queueless.ticket.service.QueueOperationsService;
import com.queueless.ticket.service.TicketService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Concurrency guarantees, exercised against real PostgreSQL row locks. Every scenario releases all
 * threads from a start gate at the same instant, so the requests genuinely overlap instead of
 * depending on lucky thread timing.
 */
class ConcurrencyIT extends IntegrationTestBase {

    @Autowired
    private TicketService tickets;

    @Autowired
    private AppointmentService appointments;

    @Autowired
    private QueueOperationsService operations;

    @Autowired
    private TransactionTemplate transactions;

    record Outcome<T>(T value, ApiException error) {

        boolean succeeded() {
            return error == null;
        }
    }

    /** Starts all tasks at the same moment and returns their outcomes in task order. */
    private <T> List<Outcome<T>> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return new Outcome<>(task.call(), null);
                    } catch (ApiException expectedBusinessFailure) {
                        return new Outcome<T>(null, expectedBusinessFailure);
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<Outcome<T>> future : futures) {
                // Any non-business exception (e.g. a unique-constraint violation) fails the test here.
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static <T> long successes(List<Outcome<T>> outcomes) {
        return outcomes.stream().filter(Outcome::succeeded).count();
    }

    private static <T> Set<ErrorCode> errorCodes(List<Outcome<T>> outcomes) {
        return outcomes.stream().filter(o -> !o.succeeded()).map(o -> o.error().code()).collect(Collectors.toSet());
    }

    private List<TestUser> users(String label, int count) {
        return IntStream.range(0, count).mapToObj(i -> data.user(label + i)).toList();
    }

    @Test
    void simultaneousWalkInsReceiveUniqueConsecutiveTokens() throws Exception {
        UUID queueId = data.queue(data.organization("tokens"));
        List<TestUser> customers = users("walkin", 30);

        List<Outcome<TicketResponse>> outcomes = runConcurrently(customers.stream()
                .<Callable<TicketResponse>>map(c -> () -> tickets.joinQueue(queueId, c.id())).toList());

        assertThat(successes(outcomes)).isEqualTo(30);
        List<Integer> tokens = outcomes.stream().map(o -> o.value().tokenNumber()).sorted().toList();
        assertThat(tokens).containsExactlyElementsOf(IntStream.rangeClosed(1, 30).boxed().toList());
        assertThat(jdbc.queryForObject("select count(distinct token_number) from queue_tickets where queue_id = ?",
                Integer.class, queueId)).isEqualTo(30);

        // One committed change -> one event, each with its own queue version.
        List<RecordingRealtimeGateway.Sent> events = realtime.sentToQueue(queueId);
        assertThat(events).hasSize(30);
        assertThat(jdbc.queryForObject("select state_version from service_queues where id = ?", Long.class, queueId))
                .isEqualTo(30L);
    }

    @Test
    void simultaneousBookingsNeverExceedSlotCapacity() throws Exception {
        UUID queueId = data.queue(data.organization("capacity"), 3);
        Instant slot = at(today().plusDays(1), 11, 0);
        List<TestUser> customers = users("booker", 15);

        List<Outcome<BookingResult>> outcomes = runConcurrently(customers.stream()
                .<Callable<BookingResult>>map(c -> () -> appointments.book(queueId, c.id(), slot, null)).toList());

        assertThat(successes(outcomes)).isEqualTo(3);
        assertThat(errorCodes(outcomes)).containsExactly(ErrorCode.SLOT_FULL);
        assertThat(jdbc.queryForObject("select count(*) from appointments where queue_id = ? and scheduled_at = ? "
                + "and status <> 'CANCELLED'", Integer.class, queueId, java.sql.Timestamp.from(slot))).isEqualTo(3);
        List<Integer> tokens = outcomes.stream().filter(Outcome::succeeded)
                .map(o -> o.value().appointment().tokenNumber()).sorted().toList();
        assertThat(tokens).containsExactly(1, 2, 3);
    }

    @Test
    void theLastPlaceInASlotGoesToExactlyOneCustomer() throws Exception {
        UUID queueId = data.queue(data.organization("last-place"), 1);
        Instant slot = at(today().plusDays(2), 9, 30);
        List<TestUser> customers = users("racer", 10);

        List<Outcome<BookingResult>> outcomes = runConcurrently(customers.stream()
                .<Callable<BookingResult>>map(c -> () -> appointments.book(queueId, c.id(), slot, null)).toList());

        assertThat(successes(outcomes)).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()).map(o -> o.error().code()))
                .allMatch(code -> code == ErrorCode.SLOT_FULL);
    }

    @Test
    void simultaneousCallNextNeverAssignsATicketTwice() throws Exception {
        TestOrg organization = data.organization("call-next");
        UUID queueId = data.queue(organization);
        List<TestUser> staff = IntStream.range(0, 4).mapToObj(i -> data.member(organization, MembershipRole.STAFF)).toList();
        List<UUID> joinOrder = new ArrayList<>();
        for (TestUser customer : users("patient", 10)) {
            joinOrder.add(tickets.joinQueue(queueId, customer.id()).id());
            clock.advance(Duration.ofSeconds(1));
        }

        List<UUID> callOrder = new ArrayList<>();
        for (int round = 0; round < 10; round++) {
            List<Outcome<BoardTicket>> outcomes = runConcurrently(IntStream.range(0, 8)
                    .<Callable<BoardTicket>>mapToObj(i -> () -> operations.callNext(queueId, staff.get(i % staff.size()).id()))
                    .toList());

            assertThat(successes(outcomes)).as("round %d", round).isEqualTo(1);
            assertThat(errorCodes(outcomes)).containsExactly(ErrorCode.ACTIVE_TICKET_EXISTS);
            assertThat(jdbc.queryForObject("select count(*) from queue_tickets where queue_id = ? "
                    + "and status in ('CALLED', 'SERVING')", Integer.class, queueId)).isEqualTo(1);

            UUID called = outcomes.stream().filter(Outcome::succeeded).findFirst().orElseThrow().value().ticketId();
            callOrder.add(called);
            operations.startService(called, staff.get(0).id());
            operations.complete(called, staff.get(0).id());
        }

        assertThat(callOrder).containsExactlyElementsOf(joinOrder); // each ticket called once, in order
        List<Outcome<BoardTicket>> drained = runConcurrently(IntStream.range(0, 4)
                .<Callable<BoardTicket>>mapToObj(i -> () -> operations.callNext(queueId, staff.get(i).id())).toList());
        assertThat(errorCodes(drained)).containsExactly(ErrorCode.NO_WAITING_TICKETS);
        assertThat(jdbc.queryForObject("select count(*) from queue_tickets where queue_id = ? and status = 'COMPLETED'",
                Integer.class, queueId)).isEqualTo(10);

        // Failed attempts published nothing: exactly one TICKET_CALLED event per successful call.
        long calledEvents = realtime.sentToQueue(queueId).stream()
                .filter(e -> RecordingRealtimeGateway.reasonOf(e) == QueueChangeReason.TICKET_CALLED).count();
        assertThat(calledEvents).isEqualTo(10);
    }

    @Test
    void duplicateBookingSubmissionsResolveToOneAppointment() throws Exception {
        UUID queueId = data.queue(data.organization("idempotent"), 5);
        TestUser customer = data.user("double-clicker");
        Instant slot = at(today().plusDays(1), 14, 0);
        UUID key = UUID.randomUUID();

        List<Outcome<BookingResult>> outcomes = runConcurrently(IntStream.range(0, 10)
                .<Callable<BookingResult>>mapToObj(i -> () -> appointments.book(queueId, customer.id(), slot, key))
                .toList());

        assertThat(successes(outcomes)).isEqualTo(10);
        assertThat(outcomes.stream().filter(o -> !o.value().replayed()).count()).isEqualTo(1);
        assertThat(outcomes.stream().map(o -> o.value().appointment().id()).distinct()).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from appointments where user_id = ?", Integer.class, customer.id()))
                .isEqualTo(1);
    }

    @Test
    void simultaneousBookingsByOneCustomerOnOneDayCreateOneAppointment() throws Exception {
        UUID queueId = data.queue(data.organization("same-day"), 5);
        TestUser customer = data.user("hoarder");
        List<Instant> slots = IntStream.range(0, 6).mapToObj(i -> at(today().plusDays(1), 10 + i, 0)).toList();

        List<Outcome<BookingResult>> outcomes = runConcurrently(slots.stream()
                .<Callable<BookingResult>>map(slot -> () -> appointments.book(queueId, customer.id(), slot, null)).toList());

        assertThat(successes(outcomes)).isEqualTo(1);
        assertThat(errorCodes(outcomes)).containsExactly(ErrorCode.ALREADY_BOOKED);
    }

    @Test
    void simultaneousCancellationsAreIdempotentAndReleaseCapacityOnce() throws Exception {
        UUID queueId = data.queue(data.organization("cancel"), 1);
        TestUser owner = data.user("canceller");
        Instant slot = at(today().plusDays(1), 15, 0);
        UUID appointmentId = appointments.book(queueId, owner.id(), slot, null).appointment().id();
        long versionBefore = jdbc.queryForObject("select state_version from service_queues where id = ?", Long.class, queueId);

        List<Outcome<AppointmentResponse>> outcomes = runConcurrently(IntStream.range(0, 8)
                .<Callable<AppointmentResponse>>mapToObj(i -> () -> appointments.cancel(appointmentId, owner.id())).toList());

        assertThat(successes(outcomes)).isEqualTo(8);
        assertThat(outcomes).allSatisfy(o -> assertThat(o.value().status().name()).isEqualTo("CANCELLED"));
        assertThat(jdbc.queryForObject("select state_version from service_queues where id = ?", Long.class, queueId))
                .isEqualTo(versionBefore + 1);
        assertThat(realtime.sentToQueue(queueId).stream()
                .filter(e -> RecordingRealtimeGateway.reasonOf(e) == QueueChangeReason.APPOINTMENT_CANCELLED).count())
                .isEqualTo(1);
        // The single place is free again.
        assertThat(appointments.book(queueId, data.user("next").id(), slot, null).replayed()).isFalse();
    }

    @Test
    void callNextRacingWithCancellationLeavesAConsistentState() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            TestOrg organization = data.organization("race");
            UUID queueId = data.queue(organization);
            TestUser staff = data.member(organization, MembershipRole.STAFF);
            TestUser customer = data.user("racer");
            UUID ticketId = tickets.joinQueue(queueId, customer.id()).id();

            List<Outcome<Object>> outcomes = runConcurrently(List.<Callable<Object>>of(
                    () -> operations.callNext(queueId, staff.id()),
                    () -> tickets.cancel(ticketId, customer.id())));

            String status = jdbc.queryForObject("select status from queue_tickets where id = ?", String.class, ticketId);
            if (outcomes.get(0).succeeded()) {
                assertThat(status).isEqualTo("CALLED");
                assertThat(outcomes.get(1).error().code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
            } else {
                assertThat(status).isEqualTo("CANCELLED");
                assertThat(outcomes.get(0).error().code()).isEqualTo(ErrorCode.NO_WAITING_TICKETS);
                assertThat(outcomes.get(1).succeeded()).isTrue();
            }
        }
    }

    @Test
    void simultaneousRegistrationsWithOneEmailCreateOneAccount() throws Exception {
        String email = "same-" + UUID.randomUUID().toString().substring(0, 8) + "@test.example";
        List<ApiClient> clients = IntStream.range(0, 6).mapToObj(i -> {
            ApiClient client = anonymous();
            client.get("/api/auth/me"); // obtain a CSRF cookie before the race starts
            return client;
        }).toList();

        List<Outcome<Integer>> outcomes = runConcurrently(clients.stream().<Callable<Integer>>map(client -> () ->
                client.post("/api/auth/register", Map.of("email", email, "fullName", "Same Person",
                        "password", "a-good-password")).status()).toList());

        List<Integer> statuses = outcomes.stream().map(Outcome::value).filter(Objects::nonNull).toList();
        assertThat(statuses).hasSize(6);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isEqualTo(1);
    }

    @Test
    void aRolledBackTransactionPublishesNothing() {
        UUID queueId = data.queue(data.organization("rollback"));
        TestUser customer = data.user("rolled-back");

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            tickets.joinQueue(queueId, customer.id()); // publishes an event and stores a notification...
            throw new IllegalStateException("simulated failure after the change"); // ...then the transaction fails
        })).isInstanceOf(IllegalStateException.class);

        assertThat(realtime.sentToQueue(queueId)).isEmpty();
        assertThat(realtime.sentToUser(customer.id())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from queue_tickets where queue_id = ?", Integer.class, queueId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ?", Integer.class, customer.id())).isZero();
    }
}
