package com.queueless.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.queueless.membership.domain.MembershipRole;
import com.queueless.support.ApiClient;
import com.queueless.support.IntegrationTestBase;
import com.queueless.support.TestData.TestOrg;
import com.queueless.support.TestData.TestUser;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Server-Sent Events over real HTTP connections. */
class RealtimeIT extends IntegrationTestBase {

    @Autowired
    private SseEmitterRegistry registry;

    private UUID queueId;
    private TestUser staffUser;

    @BeforeEach
    void setUp() {
        TestOrg organization = data.organization("realtime");
        queueId = data.queue(organization);
        staffUser = data.member(organization, MembershipRole.STAFF);
    }

    /** Opens an SSE stream and collects its lines on a background thread. */
    private BlockingQueue<String> openStream(ApiClient client, String path) throws Exception {
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        HttpRequest request = HttpRequest.newBuilder(URI.create(client.baseUrl() + path))
                .header("Accept", "text/event-stream")
                .build();
        HttpResponse<Stream<String>> response = client.http()
                .sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .get(10, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        Thread reader = new Thread(() -> response.body().forEach(lines::add), "sse-test-reader");
        reader.setDaemon(true);
        reader.start();
        return lines;
    }

    /** Waits for an event with the given name and returns its data line. */
    private static String awaitEvent(BlockingQueue<String> lines, String eventName) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean inEvent = false;
        while (System.nanoTime() < deadline) {
            String line = lines.poll(200, TimeUnit.MILLISECONDS);
            if (line == null) {
                continue;
            }
            if (line.equals("event:" + eventName)) {
                inEvent = true;
            } else if (inEvent && line.startsWith("data:")) {
                return line.substring("data:".length());
            }
        }
        throw new AssertionError("No '" + eventName + "' event received within 10 seconds");
    }

    @Test
    void subscribersReceiveMinimalQueueChangeEventsAfterCommit() throws Exception {
        ApiClient watcher = as(data.user("watcher"));
        BlockingQueue<String> lines = openStream(watcher, "/api/queues/" + queueId + "/events");
        assertThat(awaitEvent(lines, "ready")).contains(queueId.toString());

        TestUser customer = data.user("joiner");
        as(customer).post("/api/queues/" + queueId + "/tickets").expect(201);

        String data = awaitEvent(lines, "queue-changed");
        assertThat(data).contains("\"queueId\":\"" + queueId + "\"")
                .contains("\"reason\":\"TICKET_CREATED\"")
                .contains("\"version\":1");
        // No customer data is broadcast to queue subscribers.
        assertThat(data).doesNotContain(customer.email()).doesNotContain(customer.name()).doesNotContain("tokenNumber");
    }

    @Test
    void heartbeatsKeepIdleStreamsAlive() throws Exception {
        BlockingQueue<String> lines = openStream(as(data.user("idle")), "/api/queues/" + queueId + "/events");
        awaitEvent(lines, "ready");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean heartbeat = false;
        while (!heartbeat && System.nanoTime() < deadline) {
            String line = lines.poll(200, TimeUnit.MILLISECONDS);
            heartbeat = line != null && line.startsWith(":heartbeat");
        }
        assertThat(heartbeat).as("heartbeat comment received").isTrue();
    }

    @Test
    void personalStreamDeliversNotificationEvents() throws Exception {
        TestUser customer = data.user("personal");
        ApiClient client = as(customer);
        String ticketId = client.post("/api/queues/" + queueId + "/tickets").expect(201).text("id");
        BlockingQueue<String> lines = openStream(client, "/api/me/events");
        awaitEvent(lines, "ready");

        as(staffUser).post("/api/staff/queues/" + queueId + "/call-next").expect(200);

        String event = awaitEvent(lines, "notification");
        assertThat(event).contains("TURN_CALLED").contains(ticketId);
    }

    @Test
    void streamsRequireAuthenticationAndAnExistingQueue() throws Exception {
        ApiClient anonymous = anonymous();
        HttpRequest request = HttpRequest.newBuilder(URI.create(anonymous.baseUrl() + "/api/queues/" + queueId + "/events"))
                .header("Accept", "text/event-stream").build();
        assertThat(anonymous.http().send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);

        ApiClient user = as(data.user("missing"));
        HttpRequest missing = HttpRequest.newBuilder(URI.create(user.baseUrl() + "/api/queues/" + UUID.randomUUID() + "/events"))
                .header("Accept", "text/event-stream").build();
        assertThat(user.http().send(missing, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
    }

    @Test
    void closedConnectionsAreRemovedFromTheRegistry() throws Exception {
        ApiClient watcher = as(data.user("leaver"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(watcher.baseUrl() + "/api/queues/" + queueId + "/events"))
                .header("Accept", "text/event-stream").build();
        HttpResponse<Stream<String>> response = watcher.http()
                .sendAsync(request, HttpResponse.BodyHandlers.ofLines()).get(10, TimeUnit.SECONDS);
        assertThat(registry.subscribersOfQueue(queueId)).isEqualTo(1);

        response.body().close(); // the browser tab goes away
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (registry.subscribersOfQueue(queueId) > 0 && System.nanoTime() < deadline) {
            Thread.sleep(100); // the next heartbeat write fails and removes the connection
        }
        assertThat(registry.subscribersOfQueue(queueId)).isZero();
    }
}
