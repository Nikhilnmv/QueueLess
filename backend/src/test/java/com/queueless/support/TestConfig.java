package com.queueless.support;

import com.queueless.auth.domain.UserRepository;
import com.queueless.membership.domain.MembershipRepository;
import com.queueless.organization.domain.OrganizationRepository;
import com.queueless.queue.domain.ServiceQueueRepository;
import com.queueless.realtime.RecordingRealtimeGateway;
import com.queueless.realtime.SseEmitterRegistry;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

@TestConfiguration(proxyBeanMethods = false)
public class TestConfig {

    /** 10:00 in Asia/Kolkata on a Tuesday: inside the default test queue's opening hours. */
    public static final Instant DEFAULT_NOW = Instant.parse("2026-03-10T04:30:00Z");

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(DEFAULT_NOW);
    }

    @Bean
    @Primary
    RecordingRealtimeGateway recordingRealtimeGateway(SseEmitterRegistry registry) {
        return new RecordingRealtimeGateway(registry);
    }

    /**
     * Rebuilds the schema from the Flyway migrations at the start of every test run, which also
     * proves that the migrations apply cleanly. Refuses to touch any database not named *_test.
     */
    @Bean
    FlywayMigrationStrategy cleanThenMigrate(DataSource dataSource) {
        return flyway -> {
            String database = new JdbcTemplate(dataSource).queryForObject("select current_database()", String.class);
            if (database == null || !database.endsWith("_test")) {
                throw new IllegalStateException("Refusing to clean database '" + database
                        + "': integration tests require a database whose name ends with _test");
            }
            flyway.clean();
            flyway.migrate();
        };
    }

    @Bean
    TestData testData(TransactionTemplate transactions, UserRepository users, OrganizationRepository organizations,
            MembershipRepository memberships, ServiceQueueRepository queues, PasswordEncoder passwordEncoder,
            MutableClock clock) {
        return new TestData(transactions, users, organizations, memberships, queues, passwordEncoder, clock);
    }
}
