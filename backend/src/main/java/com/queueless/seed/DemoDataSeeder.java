package com.queueless.seed;

import com.queueless.auth.domain.User;
import com.queueless.auth.domain.UserRepository;
import com.queueless.common.config.QueueLessProperties;
import com.queueless.common.time.TimeProvider;
import com.queueless.membership.domain.MembershipRepository;
import com.queueless.membership.domain.MembershipRole;
import com.queueless.membership.domain.OrganizationMembership;
import com.queueless.organization.domain.Organization;
import com.queueless.organization.domain.OrganizationRepository;
import com.queueless.queue.domain.QueueSettings;
import com.queueless.queue.domain.QueueStatus;
import com.queueless.queue.domain.ServiceQueue;
import com.queueless.queue.domain.ServiceQueueRepository;
import com.queueless.ticket.domain.QueueTicketRepository;
import com.queueless.ticket.domain.TicketPriority;
import com.queueless.ticket.service.TicketService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Idempotent demo data: two organizations (to demonstrate tenant isolation), their queues, staff
 * and admins, three customers and a platform administrator. Records are created only when
 * missing, so restarting never duplicates anything. Runs only when queueless.seed.enabled=true
 * (always in the dev profile; opt-in in production via QUEUELESS_SEED_DEMO_DATA). All people and
 * email addresses are fictional.
 */
@Component
@ConditionalOnProperty(name = "queueless.seed.enabled", havingValue = "true")
class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    record SeedUser(String email, String name) {}

    static final SeedUser PLATFORM_ADMIN = new SeedUser("platform@queueless.test", "Platform Operator");
    static final SeedUser CLINIC_ADMIN = new SeedUser("admin@citycare.example", "City Care Admin");
    static final SeedUser CLINIC_STAFF = new SeedUser("staff@citycare.example", "City Care Reception");
    static final SeedUser COLLEGE_ADMIN = new SeedUser("admin@northbridge.example", "Northbridge Admin");
    static final SeedUser COLLEGE_STAFF = new SeedUser("staff@northbridge.example", "Northbridge Desk");
    static final List<SeedUser> CUSTOMERS = List.of(
            new SeedUser("asha@example.com", "Asha Demo"),
            new SeedUser("ravi@example.com", "Ravi Demo"),
            new SeedUser("meera@example.com", "Meera Demo"));

    private final UserRepository users;
    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final ServiceQueueRepository queues;
    private final QueueTicketRepository tickets;
    private final TicketService ticketService;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactions;
    private final TimeProvider time;
    private final QueueLessProperties.Seed settings;

    DemoDataSeeder(UserRepository users, OrganizationRepository organizations, MembershipRepository memberships,
            ServiceQueueRepository queues, QueueTicketRepository tickets, TicketService ticketService,
            PasswordEncoder passwordEncoder, TransactionTemplate transactions, TimeProvider time,
            QueueLessProperties properties) {
        this.users = users;
        this.organizations = organizations;
        this.memberships = memberships;
        this.queues = queues;
        this.tickets = tickets;
        this.ticketService = ticketService;
        this.passwordEncoder = passwordEncoder;
        this.transactions = transactions;
        this.time = time;
        this.settings = properties.seed();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(settings.password())) {
            throw new IllegalStateException(
                    "Demo seeding is enabled but QUEUELESS_SEED_PASSWORD is not set. Set it, or disable seeding.");
        }
        transactions.executeWithoutResult(status -> seedReferenceData());
        if (settings.sampleTickets()) {
            seedTodaysSampleTickets();
        }
        log.info("Demo data ready (organizations: city-care-clinic, northbridge-college)");
    }

    private void seedReferenceData() {
        User platformAdmin = user(PLATFORM_ADMIN);
        if (!platformAdmin.isPlatformAdmin()) {
            platformAdmin.grantPlatformAdmin(time.now());
        }

        Organization clinic = organization("City Care Clinic", "city-care-clinic",
                "Outpatient clinic: general consultation and laboratory sample collection.");
        member(clinic, user(CLINIC_ADMIN), MembershipRole.ADMIN);
        member(clinic, user(CLINIC_STAFF), MembershipRole.STAFF);
        queue(clinic, new QueueSettings("General OPD", "Consultation with the duty doctor.", "G",
                LocalTime.of(9, 0), LocalTime.of(17, 0), 15, 2, 10, true));
        queue(clinic, new QueueSettings("Lab Sample Collection", "Blood and urine sample collection.", "L",
                LocalTime.of(8, 0), LocalTime.of(13, 0), 10, 3, 5, true));

        Organization college = organization("Northbridge College Office", "northbridge-college",
                "Student services: admission enquiries and fee payments.");
        member(college, user(COLLEGE_ADMIN), MembershipRole.ADMIN);
        member(college, user(COLLEGE_STAFF), MembershipRole.STAFF);
        queue(college, new QueueSettings("Admissions Desk", "Admission enquiries and document verification.", "A",
                LocalTime.of(10, 0), LocalTime.of(16, 0), 20, 1, 15, true));
        queue(college, new QueueSettings("Fee Counter", "Fee payments and receipts.", "F",
                LocalTime.of(10, 0), LocalTime.of(15, 0), 15, 2, 8, true));

        CUSTOMERS.forEach(this::user);
    }

    /** Development only: a few people already waiting today, created through the real ticket service. */
    private void seedTodaysSampleTickets() {
        Organization clinic = organizations.findBySlug("city-care-clinic").orElseThrow();
        ServiceQueue opd = queues.findByOrganizationIdOrderByNameAsc(clinic.getId()).stream()
                .filter(q -> q.getName().equals("General OPD")).findFirst().orElseThrow();
        LocalDate today = time.today(clinic.zoneId());
        if (tickets.findMaxTokenNumber(opd.getId(), today) > 0) {
            return;
        }
        User staff = users.findByEmail(CLINIC_STAFF.email()).orElseThrow();
        ticketService.registerGuest(opd.getId(), staff.getId(), "Walk-in Guest One", TicketPriority.NORMAL);
        ticketService.registerGuest(opd.getId(), staff.getId(), "Walk-in Guest Two", TicketPriority.NORMAL);
        ticketService.joinQueue(opd.getId(), users.findByEmail(CUSTOMERS.get(1).email()).orElseThrow().getId());
        log.info("Seeded sample tickets for {} in General OPD", today);
    }

    private User user(SeedUser seed) {
        return users.findByEmail(seed.email()).orElseGet(() -> users.save(
                new User(seed.email(), seed.name(), passwordEncoder.encode(settings.password()), time.now())));
    }

    private Organization organization(String name, String slug, String description) {
        return organizations.findBySlug(slug).orElseGet(() ->
                organizations.save(new Organization(name, slug, description, INDIA, time.now())));
    }

    private void member(Organization organization, User user, MembershipRole role) {
        if (memberships.findByUserIdAndOrganizationId(user.getId(), organization.getId()).isEmpty()) {
            memberships.save(new OrganizationMembership(organization.getId(), user.getId(), role, time.now()));
        }
    }

    private void queue(Organization organization, QueueSettings settings) {
        if (!queues.existsByOrganizationIdAndNameIgnoreCase(organization.getId(), settings.name())) {
            ServiceQueue queue = new ServiceQueue(organization.getId(), settings, time.now());
            queue.changeStatus(QueueStatus.OPEN, time.now());
            queues.save(queue);
        }
    }
}
