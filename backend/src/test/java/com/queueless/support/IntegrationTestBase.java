package com.queueless.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueless.realtime.RecordingRealtimeGateway;
import com.queueless.support.TestData.TestUser;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base for integration tests: the full application on a random port, backed by the real
 * PostgreSQL test database (see application-test.yml). All test classes share one Spring context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestConfig.class)
public abstract class IntegrationTestBase {

    @LocalServerPort
    protected int port;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected TestData data;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected RecordingRealtimeGateway realtime;

    @BeforeEach
    void resetClock() {
        clock.setInstant(TestConfig.DEFAULT_NOW);
    }

    protected ApiClient anonymous() {
        return new ApiClient("http://localhost:" + port, objectMapper);
    }

    protected ApiClient as(TestUser user) {
        return anonymous().loginOrFail(user.email(), TestData.PASSWORD);
    }

    protected LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), TestData.ZONE);
    }

    /** An instant at the given organization-local date and time. */
    protected static Instant at(LocalDate date, int hour, int minute) {
        return ZonedDateTime.of(date, LocalTime.of(hour, minute), TestData.ZONE).toInstant();
    }
}
