package com.queueless.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * A minimal browser stand-in for HTTP-level tests: keeps cookies (session + XSRF-TOKEN) and sends
 * the X-XSRF-TOKEN header on writes, exactly like the React client.
 */
public class ApiClient {

    public record ApiResponse(int status, JsonNode body, HttpHeaders headers) {

        public String code() {
            return body == null ? null : body.path("code").asText(null);
        }

        public String text(String field) {
            return body.path(field).asText(null);
        }

        public ApiResponse expect(int expectedStatus) {
            assertThat(status).as("HTTP status; body: %s", body).isEqualTo(expectedStatus);
            return this;
        }
    }

    private final String baseUrl;
    private final ObjectMapper mapper;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;

    public ApiClient(String baseUrl, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public HttpClient http() {
        return http;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public ApiResponse get(String path) {
        return send("GET", path, null, Map.of());
    }

    public ApiResponse post(String path) {
        return send("POST", path, null, Map.of());
    }

    public ApiResponse post(String path, Object body) {
        return send("POST", path, body, Map.of());
    }

    public ApiResponse post(String path, Object body, Map<String, String> headers) {
        return send("POST", path, body, headers);
    }

    public ApiResponse put(String path, Object body) {
        return send("PUT", path, body, Map.of());
    }

    public ApiResponse patch(String path, Object body) {
        return send("PATCH", path, body, Map.of());
    }

    public ApiResponse delete(String path) {
        return send("DELETE", path, null, Map.of());
    }

    /** POST without the CSRF header (to prove the server rejects it). */
    public ApiResponse postWithoutCsrf(String path, Object body) {
        return execute(request("POST", path, body, Map.of(), false));
    }

    public ApiResponse login(String email, String password) {
        return post("/api/auth/login", Map.of("email", email, "password", password));
    }

    public ApiClient loginOrFail(String email, String password) {
        login(email, password).expect(200);
        return this;
    }

    public Optional<String> cookie(String name) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals(name))
                .map(HttpCookie::getValue)
                .findFirst();
    }

    private ApiResponse send(String method, String path, Object body, Map<String, String> headers) {
        if (!method.equals("GET") && cookie("XSRF-TOKEN").isEmpty()) {
            get("/api/auth/me"); // any request issues the XSRF-TOKEN cookie
        }
        return execute(request(method, path, body, headers, true));
    }

    private HttpRequest request(String method, String path, Object body, Map<String, String> headers, boolean csrf) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        headers.forEach(builder::header);
        if (csrf) {
            cookie("XSRF-TOKEN").ifPresent(token -> builder.header("X-XSRF-TOKEN", token));
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(json(body)));
        }
        return builder.build();
    }

    private ApiResponse execute(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body() == null || response.body().isBlank() ? null : mapper.readTree(response.body());
            return new ApiResponse(response.statusCode(), body, response.headers());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private String json(Object body) {
        try {
            return body instanceof String s ? s : mapper.writeValueAsString(body);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
