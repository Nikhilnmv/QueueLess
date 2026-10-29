package com.queueless.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.queueless.membership.domain.MembershipRole;
import com.queueless.support.ApiClient;
import com.queueless.support.IntegrationTestBase;
import com.queueless.support.TestData.TestOrg;
import java.time.Duration;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/**
 * Regression test: organization-local calendar values (opening hours, service dates) must be
 * stored verbatim, whatever the JVM's default time zone is. An earlier configuration
 * (hibernate.jdbc.time_zone=UTC) silently shifted LocalTime values by the JVM offset (09:00 was
 * stored as 03:30 in IST), which only became visible when a shifted range crossed midnight.
 * The failsafe JVM runs in Asia/Kolkata so this test is meaningful on UTC machines too.
 */
class TimeZoneStorageIT extends IntegrationTestBase {

    @Test
    void localTimesAndDatesAreStoredWithoutShifting() {
        assertThat(TimeZone.getDefault().getRawOffset()).as("test JVM must not run in UTC").isNotZero();

        TestOrg organization = data.organization("tz");
        ApiClient admin = as(data.member(organization, MembershipRole.ADMIN));
        String queueId = admin.post("/api/admin/organizations/" + organization.id() + "/queues", Map.of(
                "name", "All day", "tokenPrefix", "D", "openingTime", "00:00", "closingTime", "23:45",
                "slotDurationMinutes", 15, "capacityPerSlot", 1, "defaultServiceMinutes", 5, "bookingEnabled", true))
                .expect(201).text("id");

        Map<String, Object> hours = jdbc.queryForMap(
                "select opening_time::text as opening, closing_time::text as closing from service_queues where id = ?::uuid",
                queueId);
        assertThat(hours).containsEntry("opening", "00:00:00").containsEntry("closing", "23:45:00");

        // 23:50 in Kolkata (18:20 UTC): the service date must be the local date, not the UTC one.
        clock.setInstant(at(today(), 23, 50).minus(Duration.ZERO));
        admin.post("/api/staff/queues/" + queueId + "/status", Map.of("status", "OPEN")).expect(200);
        String ticketId = as(data.user("late-night")).post("/api/queues/" + queueId + "/tickets").expect(201).text("id");
        String serviceDate = jdbc.queryForObject("select service_date::text from queue_tickets where id = ?::uuid",
                String.class, ticketId);
        assertThat(serviceDate).isEqualTo(today().toString());

        // And the API reports the configured hours unchanged.
        assertThat(anonymous().get("/api/queues/" + queueId).expect(200).text("openingTime")).startsWith("00:00");
    }
}
