package com.queueless.membership;

import static org.assertj.core.api.Assertions.assertThat;

import com.queueless.membership.domain.MembershipRole;
import com.queueless.support.ApiClient;
import com.queueless.support.IntegrationTestBase;
import com.queueless.support.TestData.TestOrg;
import com.queueless.support.TestData.TestUser;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Users of organization B must not read or change organization A's private data by editing ids in
 * URLs or bodies. Private resources of other users answer 404 (existence is not revealed); staff
 * operations on a public queue of another organization answer 403.
 */
class TenantIsolationIT extends IntegrationTestBase {

    private TestOrg orgA;
    private TestOrg orgB;
    private UUID queueA;
    private UUID queueB;
    private TestUser adminA;
    private TestUser staffA;
    private TestUser adminB;
    private TestUser staffB;
    private TestUser customer;
    private TestUser otherCustomer;

    @BeforeEach
    void createTwoOrganizations() {
        orgA = data.organization("clinic");
        orgB = data.organization("college");
        queueA = data.queue(orgA);
        queueB = data.queue(orgB);
        adminA = data.member(orgA, MembershipRole.ADMIN);
        staffA = data.member(orgA, MembershipRole.STAFF);
        adminB = data.member(orgB, MembershipRole.ADMIN);
        staffB = data.member(orgB, MembershipRole.STAFF);
        customer = data.user("customer");
        otherCustomer = data.user("other");
    }

    @Test
    void staffOfAnotherOrganizationCannotOperateTheQueue() {
        as(customer).post("/api/queues/" + queueA + "/tickets").expect(201);
        ApiClient foreignStaff = as(staffB);

        assertThat(foreignStaff.get("/api/staff/queues/" + queueA + "/board").expect(403).code()).isEqualTo("ACCESS_DENIED");
        foreignStaff.post("/api/staff/queues/" + queueA + "/call-next").expect(403);
        foreignStaff.post("/api/staff/queues/" + queueA + "/status", Map.of("status", "CLOSED")).expect(403);
        foreignStaff.post("/api/staff/queues/" + queueA + "/walk-ins", Map.of("guestName", "Intruder")).expect(403);

        // ...while their own organization's queue works normally.
        foreignStaff.get("/api/staff/queues/" + queueB + "/board").expect(200);
        // and the real staff of A still sees the untouched queue.
        assertThat(as(staffA).get("/api/staff/queues/" + queueA + "/board").expect(200)
                .body().path("waiting")).hasSize(1);
    }

    @Test
    void staffOfAnotherOrganizationCannotTouchItsTickets() {
        String ticketId = as(customer).post("/api/queues/" + queueA + "/tickets").expect(201).text("id");
        ApiClient foreignStaff = as(staffB);

        foreignStaff.get("/api/tickets/" + ticketId).expect(404);
        foreignStaff.post("/api/tickets/" + ticketId + "/cancel").expect(404);
        foreignStaff.patch("/api/staff/tickets/" + ticketId + "/priority", Map.of("priority", "HIGH")).expect(404);
        foreignStaff.post("/api/staff/tickets/" + ticketId + "/start").expect(404);
        foreignStaff.post("/api/staff/tickets/" + ticketId + "/no-show").expect(404);

        as(staffA).get("/api/tickets/" + ticketId).expect(200);
    }

    @Test
    void adminOfAnotherOrganizationCannotManageIt() {
        ApiClient foreignAdmin = as(adminB);
        Map<String, Object> config = Map.of("name", "Hijacked", "tokenPrefix", "H", "openingTime", "09:00",
                "closingTime", "17:00", "slotDurationMinutes", 15, "capacityPerSlot", 2,
                "defaultServiceMinutes", 10, "bookingEnabled", true);

        foreignAdmin.get("/api/admin/organizations/" + orgA.id()).expect(403);
        foreignAdmin.patch("/api/admin/organizations/" + orgA.id(), Map.of("name", "Hijacked")).expect(403);
        foreignAdmin.get("/api/admin/organizations/" + orgA.id() + "/members").expect(403);
        foreignAdmin.post("/api/admin/organizations/" + orgA.id() + "/members",
                Map.of("email", adminB.email(), "role", "ADMIN")).expect(403);
        foreignAdmin.post("/api/admin/organizations/" + orgA.id() + "/queues", config).expect(403);
        foreignAdmin.put("/api/admin/queues/" + queueA, config).expect(403);

        String queueName = jdbc.queryForObject("select name from service_queues where id = ?", String.class, queueA);
        assertThat(queueName).isNotEqualTo("Hijacked");
        Integer memberships = jdbc.queryForObject(
                "select count(*) from organization_memberships where organization_id = ? and user_id = ?",
                Integer.class, orgA.id(), adminB.id());
        assertThat(memberships).isZero();
    }

    @Test
    void anAdminsOwnQueueIdDoesNotUnlockAnotherOrganization() {
        // Admin A owns queueA but tries to update queueB through the same endpoint.
        as(adminA).put("/api/admin/queues/" + queueB, Map.of("name", "Mine now", "tokenPrefix", "M",
                "openingTime", "09:00", "closingTime", "17:00", "slotDurationMinutes", 15, "capacityPerSlot", 2,
                "defaultServiceMinutes", 10, "bookingEnabled", true)).expect(403);
    }

    @Test
    void customersCannotSeeOrChangeEachOthersBookings() {
        ApiClient owner = as(customer);
        String appointmentId = owner.post("/api/queues/" + queueA + "/appointments",
                Map.of("scheduledAt", at(today().plusDays(1), 10, 0).toString())).expect(201).text("id");
        String ticketId = owner.get("/api/appointments/" + appointmentId).expect(200).text("ticketId");

        ApiClient stranger = as(otherCustomer);
        stranger.get("/api/appointments/" + appointmentId).expect(404);
        stranger.post("/api/appointments/" + appointmentId + "/cancel").expect(404);
        stranger.post("/api/appointments/" + appointmentId + "/check-in").expect(404);
        stranger.get("/api/tickets/" + ticketId).expect(404);
        stranger.post("/api/tickets/" + ticketId + "/cancel").expect(404);

        assertThat(owner.get("/api/appointments/" + appointmentId).expect(200).text("status")).isEqualTo("BOOKED");
    }

    @Test
    void customersCannotUseStaffOperationsOrRaiseTheirOwnPriority() {
        ApiClient client = as(customer);
        String ticketId = client.post("/api/queues/" + queueA + "/tickets").expect(201).text("id");

        client.post("/api/staff/queues/" + queueA + "/call-next").expect(403);
        client.get("/api/staff/queues/" + queueA + "/board").expect(403);
        client.patch("/api/staff/tickets/" + ticketId + "/priority", Map.of("priority", "HIGH")).expect(403);

        assertThat(client.get("/api/tickets/" + ticketId).expect(200).text("priority")).isEqualTo("NORMAL");
    }

    @Test
    void staffCannotManageMembersOrConfigureQueues() {
        ApiClient staff = as(staffA);
        staff.get("/api/admin/organizations/" + orgA.id()).expect(200); // read-only overview is allowed
        staff.get("/api/admin/organizations/" + orgA.id() + "/members").expect(403);
        staff.post("/api/admin/organizations/" + orgA.id() + "/members",
                Map.of("email", customer.email(), "role", "ADMIN")).expect(403);
        staff.put("/api/admin/queues/" + queueA, Map.of("name", "Renamed", "tokenPrefix", "R",
                "openingTime", "09:00", "closingTime", "17:00", "slotDurationMinutes", 15, "capacityPerSlot", 2,
                "defaultServiceMinutes", 10, "bookingEnabled", true)).expect(403);
    }

    @Test
    void adminManagesMembersButCannotRemoveTheLastAdmin() {
        ApiClient admin = as(adminA);
        String membershipId = admin.post("/api/admin/organizations/" + orgA.id() + "/members",
                Map.of("email", customer.email(), "role", "STAFF")).expect(201).text("membershipId");
        admin.post("/api/admin/organizations/" + orgA.id() + "/members",
                Map.of("email", customer.email(), "role", "ADMIN")).expect(409);

        // The new staff member can now operate queue A, and only queue A.
        ApiClient newStaff = as(customer);
        newStaff.get("/api/staff/queues/" + queueA + "/board").expect(200);
        newStaff.get("/api/staff/queues/" + queueB + "/board").expect(403);

        String adminMembership = admin.get("/api/admin/organizations/" + orgA.id() + "/members").expect(200)
                .body().findValues("membershipId").stream()
                .filter(node -> !node.asText().equals(membershipId))
                .map(node -> node.asText())
                .filter(id -> jdbc.queryForObject("select role from organization_memberships where id = ?::uuid",
                        String.class, id).equals("ADMIN"))
                .findFirst().orElseThrow();
        assertThat(admin.patch("/api/admin/organizations/" + orgA.id() + "/members/" + adminMembership,
                Map.of("role", "STAFF")).expect(409).code()).isEqualTo("LAST_ADMIN");
        admin.delete("/api/admin/organizations/" + orgA.id() + "/members/" + adminMembership).expect(409);

        // A membership id from organization A cannot be edited through organization B's URL.
        as(adminB).patch("/api/admin/organizations/" + orgB.id() + "/members/" + membershipId,
                Map.of("role", "ADMIN")).expect(404);
    }
}
