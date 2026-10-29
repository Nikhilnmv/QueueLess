package com.queueless.support;

import com.queueless.auth.domain.User;
import com.queueless.auth.domain.UserRepository;
import com.queueless.membership.domain.MembershipRepository;
import com.queueless.membership.domain.MembershipRole;
import com.queueless.membership.domain.OrganizationMembership;
import com.queueless.organization.domain.Organization;
import com.queueless.organization.domain.OrganizationRepository;
import com.queueless.queue.domain.QueueSettings;
import com.queueless.queue.domain.QueueStatus;
import com.queueless.queue.domain.ServiceQueue;
import com.queueless.queue.domain.ServiceQueueRepository;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates isolated fixtures directly through repositories (fast and independent of the API under
 * test). Every organization, queue and user gets a unique name, so tests never interfere.
 */
public class TestData {

    public static final String PASSWORD = "Test-Password-1";
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    public record TestUser(UUID id, String email, String name) {}

    public record TestOrg(UUID id, String slug, String name) {}

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final TransactionTemplate transactions;
    private final UserRepository users;
    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final ServiceQueueRepository queues;
    private final MutableClock clock;
    private final String passwordHash;

    TestData(TransactionTemplate transactions, UserRepository users, OrganizationRepository organizations,
            MembershipRepository memberships, ServiceQueueRepository queues, PasswordEncoder passwordEncoder,
            MutableClock clock) {
        this.transactions = transactions;
        this.users = users;
        this.organizations = organizations;
        this.memberships = memberships;
        this.queues = queues;
        this.clock = clock;
        this.passwordHash = passwordEncoder.encode(PASSWORD); // hash once; bcrypt is deliberately slow
    }

    public TestUser user(String label) {
        int n = SEQUENCE.incrementAndGet();
        String email = label.toLowerCase() + "-" + n + "-" + shortId() + "@test.example";
        User user = transactions.execute(status ->
                users.save(new User(email, label + " " + n, passwordHash, clock.instant())));
        return new TestUser(user.getId(), email, user.getFullName());
    }

    public TestOrg organization(String label) {
        String slug = label.toLowerCase() + "-" + shortId();
        Organization organization = transactions.execute(status ->
                organizations.save(new Organization(label + " " + slug, slug, "Test organization", ZONE, clock.instant())));
        return new TestOrg(organization.getId(), slug, organization.getName());
    }

    public TestUser member(TestOrg organization, MembershipRole role) {
        TestUser user = user(role.name().toLowerCase());
        transactions.executeWithoutResult(status -> memberships.save(
                new OrganizationMembership(organization.id(), user.id(), role, clock.instant())));
        return user;
    }

    /** Open queue, 09:00-17:00, 15-minute slots, given capacity, 10-minute default service. */
    public UUID queue(TestOrg organization, int capacityPerSlot) {
        return queue(organization, new QueueSettings("Queue " + shortId(), "Test queue", "T", LocalTime.of(9, 0),
                LocalTime.of(17, 0), 15, capacityPerSlot, 10, true));
    }

    public UUID queue(TestOrg organization) {
        return queue(organization, 2);
    }

    public UUID queue(TestOrg organization, QueueSettings settings) {
        return transactions.execute(status -> {
            ServiceQueue queue = new ServiceQueue(organization.id(), settings, clock.instant());
            queue.changeStatus(QueueStatus.OPEN, clock.instant());
            return queues.save(queue).getId();
        });
    }

    public void setStatus(UUID queueId, QueueStatus status) {
        transactions.executeWithoutResult(tx -> queues.findById(queueId).orElseThrow()
                .changeStatus(status, clock.instant()));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
