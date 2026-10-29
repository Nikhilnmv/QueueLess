package com.queueless.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.queueless.membership.domain.MembershipRole;
import com.queueless.support.ApiClient;
import com.queueless.support.ApiClient.ApiResponse;
import com.queueless.support.IntegrationTestBase;
import com.queueless.support.TestData.TestOrg;
import com.queueless.support.TestData.TestUser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueueLifecycleIT extends IntegrationTestBase {

    private TestOrg organization;
    private UUID queueId;
    private ApiClient staff;

    @BeforeEach
    void setUp() {
        organization = data.organization("lifecycle");
        queueId = data.queue(organization);
        staff = as(data.member(organization, MembershipRole.STAFF));
    }

    /**
     * Joins the queue, then moves the test clock forward one second: with a frozen clock every
     * ticket would share one check-in time and the order would fall back to the random-id tie-breaker.
     */
    private ApiResponse join(TestUser user) {
        ApiResponse response = as(user).post("/api/queues/" + queueId + "/tickets");
        clock.advance(Duration.ofSeconds(1));
        return response;
    }

    private String callNext() {
        return staff.post("/api/staff/queues/" + queueId + "/call-next").expect(200).text("ticketId");
    }

    @Test
    void walkInsReceiveConsecutiveTokensAndOneActiveTicketEach() {
        TestUser first = data.user("first");
        ApiResponse ticket = join(first).expect(201);
        assertThat(ticket.body().path("tokenNumber").asInt()).isEqualTo(1);
        assertThat(ticket.text("displayToken")).isEqualTo("T-001");
        assertThat(ticket.text("status")).isEqualTo("WAITING");
        assertThat(ticket.body().path("live").path("position").asInt()).isEqualTo(1);

        assertThat(join(data.user("second")).expect(201).body().path("tokenNumber").asInt()).isEqualTo(2);

        ApiResponse again = join(first).expect(409);
        assertThat(again.code()).isEqualTo("ALREADY_IN_QUEUE");
        assertThat(again.text("ticketId")).isEqualTo(ticket.text("id"));
    }

    @Test
    void tokensRestartEveryServiceDay() {
        join(data.user("a")).expect(201);
        join(data.user("b")).expect(201);
        clock.advance(Duration.ofDays(1));
        assertThat(join(data.user("c")).expect(201).body().path("tokenNumber").asInt()).isEqualTo(1);
    }

    @Test
    void queueStatusControlsArrivalsAndCalling() {
        join(data.user("waiting")).expect(201);

        staff.post("/api/staff/queues/" + queueId + "/status", Map.of("status", "PAUSED")).expect(200);
        join(data.user("during-pause")).expect(201);
        assertThat(staff.post("/api/staff/queues/" + queueId + "/call-next").expect(409).code()).isEqualTo("QUEUE_NOT_OPEN");

        staff.post("/api/staff/queues/" + queueId + "/status", Map.of("status", "CLOSED")).expect(200);
        assertThat(join(data.user("late")).expect(409).code()).isEqualTo("QUEUE_NOT_ACCEPTING");

        staff.post("/api/staff/queues/" + queueId + "/status", Map.of("status", "OPEN")).expect(200);
        callNext();
    }

    @Test
    void fullServiceLifecycleAndIllegalTransitions() {
        String ticketId = join(data.user("patient")).expect(201).text("id");

        assertThat(staff.post("/api/staff/tickets/" + ticketId + "/complete").expect(409).code())
                .isEqualTo("INVALID_STATE_TRANSITION"); // WAITING cannot be completed
        assertThat(callNext()).isEqualTo(ticketId);
        staff.post("/api/staff/tickets/" + ticketId + "/complete").expect(409); // CALLED cannot be completed

        assertThat(staff.post("/api/staff/tickets/" + ticketId + "/start").expect(200).text("status")).isEqualTo("SERVING");
        staff.post("/api/staff/tickets/" + ticketId + "/start").expect(409);
        staff.post("/api/staff/tickets/" + ticketId + "/no-show").expect(409); // SERVING cannot be a no-show

        clock.advance(Duration.ofMinutes(7));
        assertThat(staff.post("/api/staff/tickets/" + ticketId + "/complete").expect(200).text("status")).isEqualTo("COMPLETED");
        staff.post("/api/staff/tickets/" + ticketId + "/complete").expect(409); // terminal
        staff.post("/api/tickets/" + ticketId + "/cancel").expect(409);         // terminal
    }

    @Test
    void onlyOneTicketAtTheCounterAndNoShowFreesIt() {
        String first = join(data.user("a")).expect(201).text("id");
        String second = join(data.user("b")).expect(201).text("id");

        assertThat(callNext()).isEqualTo(first);
        ApiResponse blocked = staff.post("/api/staff/queues/" + queueId + "/call-next").expect(409);
        assertThat(blocked.code()).isEqualTo("ACTIVE_TICKET_EXISTS");

        assertThat(staff.post("/api/staff/tickets/" + first + "/no-show").expect(200).text("status")).isEqualTo("NO_SHOW");
        assertThat(callNext()).isEqualTo(second);
    }

    @Test
    void serviceOrderIsPriorityThenCheckInTime() {
        List<String> tickets = new ArrayList<>();
        for (String name : List.of("first", "second", "third")) {
            tickets.add(join(data.user(name)).expect(201).text("id"));
            clock.advance(Duration.ofMinutes(1));
        }
        String lowGuest = staff.post("/api/staff/queues/" + queueId + "/walk-ins",
                Map.of("guestName", "Low priority guest", "priority", "LOW")).expect(201).text("ticketId");
        clock.advance(Duration.ofMinutes(1));
        String highGuest = staff.post("/api/staff/queues/" + queueId + "/walk-ins",
                Map.of("guestName", "Urgent guest", "priority", "HIGH")).expect(201).text("ticketId");
        // Staff raise the last regular customer to HIGH as well; check-in time breaks the tie.
        staff.patch("/api/staff/tickets/" + tickets.get(2) + "/priority", Map.of("priority", "HIGH")).expect(200);

        JsonNode waiting = staff.get("/api/staff/queues/" + queueId + "/board").expect(200).body().path("waiting");
        List<String> boardOrder = new ArrayList<>();
        waiting.forEach(t -> boardOrder.add(t.path("ticketId").asText()));
        List<String> expected = List.of(tickets.get(2), highGuest, tickets.get(0), tickets.get(1), lowGuest);
        assertThat(boardOrder).containsExactlyElementsOf(expected);
        assertThat(waiting.get(0).path("position").asInt()).isEqualTo(1);
        assertThat(waiting.get(4).path("position").asInt()).isEqualTo(5);

        List<String> callOrder = new ArrayList<>();
        for (int i = 0; i < expected.size(); i++) {
            String called = callNext();
            callOrder.add(called);
            staff.post("/api/staff/tickets/" + called + "/start").expect(200);
            staff.post("/api/staff/tickets/" + called + "/complete").expect(200);
        }
        assertThat(callOrder).containsExactlyElementsOf(expected);
    }

    @Test
    void positionsAndEstimatesComeFromTheLiveQueue() {
        TestUser me = data.user("me");
        join(data.user("ahead-1")).expect(201);
        join(data.user("ahead-2")).expect(201);
        String myTicket = join(me).expect(201).text("id");
        ApiClient myClient = as(me);

        JsonNode live = myClient.get("/api/tickets/" + myTicket).expect(200).body().path("live");
        assertThat(live.path("position").asInt()).isEqualTo(3);
        assertThat(live.path("peopleAhead").asInt()).isEqualTo(2);
        assertThat(live.path("estimatedWaitMinutes").asInt()).isEqualTo(20); // 2 x 10-minute default
        assertThat(live.path("estimateBasis").asText()).isEqualTo("DEFAULT");

        String serving = callNext();
        staff.post("/api/staff/tickets/" + serving + "/start").expect(200);
        clock.advance(Duration.ofMinutes(4));
        live = myClient.get("/api/tickets/" + myTicket).expect(200).body().path("live");
        assertThat(live.path("position").asInt()).isEqualTo(2);
        assertThat(live.path("nowServing").asText()).isEqualTo("T-001");
        assertThat(live.path("estimatedWaitMinutes").asInt()).isEqualTo(16); // 6 left + 1 x 10

        JsonNode summary = anonymous().get("/api/queues/" + queueId + "/live").expect(200).body();
        assertThat(summary.path("waitingCount").asInt()).isEqualTo(2);
        assertThat(summary.path("nowServing").path("displayToken").asText()).isEqualTo("T-001");
        assertThat(summary.path("estimatedWaitMinutesForNewTicket").asInt()).isEqualTo(26); // 6 + 2 x 10
        assertThat(summary.toString()).doesNotContain(me.email()).doesNotContain(me.name());
    }

    @Test
    void averageServiceTimeSwitchesToHistoryAfterEnoughCompletions() {
        for (int i = 0; i < 3; i++) {
            join(data.user("served-" + i)).expect(201);
        }
        for (int i = 0; i < 3; i++) {
            String id = callNext();
            staff.post("/api/staff/tickets/" + id + "/start").expect(200);
            clock.advance(Duration.ofMinutes(4));
            staff.post("/api/staff/tickets/" + id + "/complete").expect(200);
        }
        JsonNode summary = anonymous().get("/api/queues/" + queueId + "/live").expect(200).body();
        assertThat(summary.path("estimateBasis").asText()).isEqualTo("HISTORY");
        assertThat(summary.path("averageServiceMinutes").asInt()).isEqualTo(4);
        assertThat(summary.path("estimateSamples").asInt()).isEqualTo(3);

        JsonNode stats = staff.get("/api/staff/queues/" + queueId + "/board").expect(200).body().path("stats");
        assertThat(stats.path("completed").asInt()).isEqualTo(3);
    }

    @Test
    void customersAreNotifiedWhenTheirTurnApproachesAndWhenCalled() {
        List<TestUser> customers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            TestUser customer = data.user("notify-" + i);
            customers.add(customer);
            join(customer).expect(201);
        }
        // Positions 1-3 were told immediately; positions 4 and 5 not yet.
        assertThat(notificationTypes(customers.get(2))).containsExactly("TURN_APPROACHING");
        assertThat(notificationTypes(customers.get(3))).isEmpty();

        String calledId = callNext();
        assertThat(notificationTypes(customers.get(0))).containsExactlyInAnyOrder("TURN_APPROACHING", "TURN_CALLED");
        assertThat(notificationTypes(customers.get(3))).containsExactly("TURN_APPROACHING"); // moved up to #3
        assertThat(notificationTypes(customers.get(4))).isEmpty();

        staff.post("/api/staff/tickets/" + calledId + "/no-show").expect(200);
        assertThat(notificationTypes(customers.get(0))).contains("MARKED_NO_SHOW");
    }

    @Test
    void notificationsPersistAcrossLoginsAndOnlyTheOwnerCanReadThem() {
        TestUser customer = data.user("persist");
        join(customer).expect(201);
        callNext();

        ApiClient session = as(customer);
        JsonNode list = session.get("/api/me/notifications").expect(200).body();
        assertThat(list.path("unreadCount").asInt()).isEqualTo(2);
        session.post("/api/auth/logout").expect(204);

        ApiClient later = as(customer);
        list = later.get("/api/me/notifications").expect(200).body();
        assertThat(list.path("items")).hasSize(2);
        String notificationId = list.path("items").get(0).path("id").asText();

        as(data.user("stranger")).post("/api/me/notifications/" + notificationId + "/read").expect(404);
        assertThat(later.post("/api/me/notifications/" + notificationId + "/read").expect(200).text("readAt")).isNotNull();
        assertThat(later.get("/api/me/notifications").expect(200).body().path("unreadCount").asInt()).isEqualTo(1);
        assertThat(later.post("/api/me/notifications/read-all").expect(200).body().path("unreadCount").asInt()).isZero();
    }

    @Test
    void customersCanLeaveTheQueueButNotOnceCalled() {
        TestUser leaving = data.user("leaving");
        String leavingTicket = join(leaving).expect(201).text("id");
        TestUser staying = data.user("staying");
        String stayingTicket = join(staying).expect(201).text("id");

        // A cancelled ticket leaves the line and positions close up.
        assertThat(as(leaving).post("/api/tickets/" + leavingTicket + "/cancel").expect(200).text("status")).isEqualTo("CANCELLED");
        assertThat(as(staying).get("/api/tickets/" + stayingTicket).expect(200).body().path("live").path("position").asInt())
                .isEqualTo(1);

        assertThat(callNext()).isEqualTo(stayingTicket);
        assertThat(as(staying).post("/api/tickets/" + stayingTicket + "/cancel").expect(409).code())
                .isEqualTo("INVALID_STATE_TRANSITION");
    }

    @Test
    void guestWalkInsAreRegisteredByStaffOnly() {
        ApiResponse guest = staff.post("/api/staff/queues/" + queueId + "/walk-ins",
                Map.of("guestName", "  Elderly visitor  ")).expect(201);
        assertThat(guest.text("holderName")).isEqualTo("Elderly visitor");
        assertThat(guest.text("source")).isEqualTo("GUEST_WALK_IN");
        assertThat(guest.text("priority")).isEqualTo("NORMAL");

        staff.post("/api/staff/queues/" + queueId + "/walk-ins", Map.of("guestName", "")).expect(400);
        as(data.user("customer")).post("/api/staff/queues/" + queueId + "/walk-ins", Map.of("guestName", "Me")).expect(403);
    }

    @Test
    void myTicketsShowsHistoryAndMarksStaleTicketsExpired() {
        TestUser customer = data.user("history");
        join(customer).expect(201);
        clock.advance(Duration.ofDays(1));
        JsonNode mine = as(customer).get("/api/me/tickets").expect(200).body();
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).path("expired").asBoolean()).isTrue();
        assertThat(mine.get(0).path("live").isNull()).isTrue();
    }

    @Test
    void adminCreatesAndReconfiguresQueues() {
        ApiClient admin = as(data.member(organization, MembershipRole.ADMIN));
        Map<String, Object> config = new HashMap<>(Map.of("name", "Pharmacy", "tokenPrefix", "ph",
                "openingTime", "08:30", "closingTime", "12:30", "slotDurationMinutes", 20, "capacityPerSlot", 3,
                "defaultServiceMinutes", 6, "bookingEnabled", true));
        ApiResponse created = admin.post("/api/admin/organizations/" + organization.id() + "/queues", config).expect(201);
        assertThat(created.text("status")).isEqualTo("CLOSED");
        assertThat(created.text("tokenPrefix")).isEqualTo("PH");
        admin.post("/api/admin/organizations/" + organization.id() + "/queues", config).expect(409); // duplicate name

        config.put("closingTime", "08:00");
        assertThat(admin.put("/api/admin/queues/" + created.text("id"), config).expect(400).code()).isEqualTo("VALIDATION_FAILED");
        config.put("closingTime", "13:30");
        config.put("capacityPerSlot", 0);
        admin.put("/api/admin/queues/" + created.text("id"), config).expect(400);
        config.put("capacityPerSlot", 4);
        assertThat(admin.put("/api/admin/queues/" + created.text("id"), config).expect(200).body()
                .path("capacityPerSlot").asInt()).isEqualTo(4);

        JsonNode slots = anonymous().get("/api/queues/" + created.text("id") + "/slots?date=" + today().plusDays(1))
                .expect(200).body().path("slots");
        assertThat(slots).hasSize(15); // 08:30-13:30 in 20-minute slots
    }

    private List<String> notificationTypes(TestUser user) {
        return jdbc.queryForList("select type from notifications where user_id = ? order by created_at", String.class,
                user.id());
    }
}
