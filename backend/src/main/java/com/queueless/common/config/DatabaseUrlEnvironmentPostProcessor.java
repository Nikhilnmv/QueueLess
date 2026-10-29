package com.queueless.common.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Accepts provider-style PostgreSQL URLs for {@code spring.datasource.url}.
 *
 * <p>Neon and most hosts hand out connection strings such as
 * {@code postgresql://user:password@host/db?sslmode=require}, while the JDBC driver needs
 * {@code jdbc:postgresql://host/db?sslmode=require} plus separate credentials. When the
 * resolved datasource URL is in the provider format, it is converted here, before the
 * DataSource is created. JDBC URLs are left untouched.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "queuelessConvertedDatabaseUrl";

    /** libpq-only parameters that the JDBC driver does not understand. */
    private static final Set<String> UNSUPPORTED_PARAMETERS = Set.of("channel_binding");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty("spring.datasource.url");
        if (!StringUtils.hasText(url) || url.startsWith("jdbc:")) {
            return;
        }
        ConvertedUrl converted = convert(url.trim());
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.url", converted.jdbcUrl());
        if (converted.username() != null && !StringUtils.hasText(environment.getProperty("spring.datasource.username"))) {
            properties.put("spring.datasource.username", converted.username());
        }
        if (converted.password() != null && !StringUtils.hasText(environment.getProperty("spring.datasource.password"))) {
            properties.put("spring.datasource.password", converted.password());
        }
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, properties));
    }

    @Override
    public int getOrder() {
        // Run after application.yml and profile files have been loaded.
        return Ordered.LOWEST_PRECEDENCE;
    }

    static ConvertedUrl convert(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("DATABASE_URL is not a valid URL", ex);
        }
        String scheme = uri.getScheme();
        if (!"postgresql".equals(scheme) && !"postgres".equals(scheme)) {
            throw new IllegalArgumentException(
                    "DATABASE_URL must start with jdbc:postgresql://, postgresql:// or postgres://");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("DATABASE_URL must include a host name");
        }

        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() > 0) {
            jdbc.append(':').append(uri.getPort());
        }
        String path = uri.getRawPath();
        jdbc.append(StringUtils.hasText(path) ? path : "/");
        String query = filterQuery(uri.getRawQuery());
        if (!query.isEmpty()) {
            jdbc.append('?').append(query);
        }

        String username = null;
        String password = null;
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            int separator = userInfo.indexOf(':');
            username = decode(separator >= 0 ? userInfo.substring(0, separator) : userInfo);
            password = separator >= 0 ? decode(userInfo.substring(separator + 1)) : null;
        }
        return new ConvertedUrl(jdbc.toString(), username, password);
    }

    private static String filterQuery(String rawQuery) {
        if (!StringUtils.hasText(rawQuery)) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String parameter : rawQuery.split("&")) {
            String name = parameter.contains("=") ? parameter.substring(0, parameter.indexOf('=')) : parameter;
            if (!parameter.isEmpty() && !UNSUPPORTED_PARAMETERS.contains(name)) {
                kept.add(parameter);
            }
        }
        return String.join("&", kept);
    }

    private static String decode(String value) {
        // URLDecoder treats '+' as a space; in the user-info part '+' is a literal character.
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    record ConvertedUrl(String jdbcUrl, String username, String password) {}
}
