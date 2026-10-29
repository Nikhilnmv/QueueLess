package com.queueless.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.queueless.membership.domain.MembershipRole;
import com.queueless.support.ApiClient;
import com.queueless.support.ApiClient.ApiResponse;
import com.queueless.support.IntegrationTestBase;
import com.queueless.support.TestData;
import com.queueless.support.TestData.TestOrg;
import com.queueless.support.TestData.TestUser;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthIT extends IntegrationTestBase {

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.example";
    }

    @Test
    void registrationCreatesACustomerAndStartsASession() {
        ApiClient client = anonymous();
        String email = uniqueEmail("New.Person");

        ApiResponse response = client.post("/api/auth/register",
                Map.of("email", "  " + email.toUpperCase() + " ", "fullName", "New Person", "password", "a-good-password"))
                .expect(201);

        assertThat(response.text("email")).isEqualTo(email.toLowerCase());
        assertThat(response.body().path("platformAdmin").asBoolean()).isFalse();
        assertThat(response.body().path("memberships")).isEmpty();
        assertThat(response.body().toString()).doesNotContain("password");
        assertThat(client.cookie("QUEUELESS_SESSION")).isPresent();

        client.get("/api/auth/me").expect(200);
        String storedHash = jdbc.queryForObject("select password_hash from users where email = ?", String.class,
                email.toLowerCase());
        assertThat(storedHash).startsWith("{bcrypt}").doesNotContain("a-good-password");
    }

    @Test
    void sessionsAreStoredInPostgresql() {
        TestUser user = data.user("session");
        as(user);
        Integer sessions = jdbc.queryForObject("select count(*) from spring_session where principal_name = ?",
                Integer.class, user.email());
        assertThat(sessions).isEqualTo(1);
    }

    @Test
    void registrationCannotGrantPrivilegedRoles() {
        ApiClient client = anonymous();
        ApiResponse response = client.post("/api/auth/register", Map.of(
                "email", uniqueEmail("sneaky"), "fullName", "Sneaky", "password", "a-good-password",
                "platformAdmin", true, "role", "ADMIN", "memberships", "ADMIN")).expect(201);

        assertThat(response.body().path("platformAdmin").asBoolean()).isFalse();
        assertThat(response.body().path("memberships")).isEmpty();
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() {
        TestUser existing = data.user("dup");
        anonymous().post("/api/auth/register",
                        Map.of("email", existing.email().toUpperCase(), "fullName", "Again", "password", "a-good-password"))
                .expect(409);
    }

    @Test
    void invalidRegistrationReturnsFieldErrors() {
        ApiResponse response = anonymous().post("/api/auth/register",
                Map.of("email", "not-an-email", "fullName", "X", "password", "short")).expect(400);

        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.body().path("errors").findValuesAsText("field"))
                .contains("email", "fullName", "password");
    }

    @Test
    void loginFailuresUseOneGenericMessage() {
        TestUser user = data.user("login");
        ApiResponse wrongPassword = anonymous().login(user.email(), "wrong-password").expect(401);
        ApiResponse unknownUser = anonymous().login(uniqueEmail("nobody"), "wrong-password").expect(401);

        assertThat(wrongPassword.code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(unknownUser.code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(wrongPassword.text("detail")).isEqualTo(unknownUser.text("detail"));
    }

    @Test
    void loginReturnsMembershipsAndLogoutEndsTheSession() {
        TestOrg organization = data.organization("auth-org");
        TestUser staff = data.member(organization, MembershipRole.STAFF);

        ApiClient client = anonymous();
        ApiResponse login = client.login(staff.email(), TestData.PASSWORD).expect(200);
        assertThat(login.body().path("memberships").get(0).path("role").asText()).isEqualTo("STAFF");
        assertThat(login.body().path("memberships").get(0).path("organizationSlug").asText())
                .isEqualTo(organization.slug());

        client.post("/api/auth/logout").expect(204);
        client.get("/api/auth/me").expect(401);
    }

    @Test
    void writesWithoutTheCsrfHeaderAreRejected() {
        TestUser user = data.user("csrf");
        ApiClient client = anonymous();
        client.get("/api/auth/me"); // obtain the cookie, but do not echo it

        ApiResponse response = client.postWithoutCsrf("/api/auth/login",
                Map.of("email", user.email(), "password", TestData.PASSWORD)).expect(403);
        assertThat(response.code()).isEqualTo("CSRF_REJECTED");
    }

    @Test
    void protectedEndpointsRequireAuthentication() {
        ApiClient client = anonymous();
        assertThat(client.get("/api/me/appointments").expect(401).code()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(client.get("/api/me/notifications").status()).isEqualTo(401);
        assertThat(client.get("/api/staff/queues/" + UUID.randomUUID() + "/board").status()).isEqualTo(401);
        assertThat(client.post("/api/queues/" + UUID.randomUUID() + "/tickets").status()).isEqualTo(401);
    }

    @Test
    void repeatedFailuresLockTheAccountTemporarily() {
        TestUser user = data.user("throttle");
        ApiClient client = anonymous();
        for (int i = 0; i < 5; i++) {
            client.login(user.email(), "wrong-password").expect(401);
        }
        ApiResponse blocked = client.login(user.email(), TestData.PASSWORD).expect(429);
        assertThat(blocked.code()).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(blocked.headers().firstValue("Retry-After")).isPresent();

        clock.advance(Duration.ofMinutes(16));
        client.login(user.email(), TestData.PASSWORD).expect(200);
    }

    @Test
    void platformEndpointsRequireThePlatformRole() {
        TestOrg organization = data.organization("platform-check");
        TestUser admin = data.member(organization, MembershipRole.ADMIN);
        as(admin).post("/api/platform/organizations", Map.of("name", "X", "slug", "x-org", "timezone", "UTC",
                "adminEmail", admin.email())).expect(403);
    }
}
