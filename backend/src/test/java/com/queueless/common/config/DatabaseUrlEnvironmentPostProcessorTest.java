package com.queueless.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queueless.common.config.DatabaseUrlEnvironmentPostProcessor.ConvertedUrl;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class DatabaseUrlEnvironmentPostProcessorTest {

    @Test
    void convertsAProviderConnectionStringWithEncodedCredentials() {
        ConvertedUrl converted = DatabaseUrlEnvironmentPostProcessor.convert(
                "postgresql://app_user:p%40ss%2Bword@ep-cool-name-123.ap-southeast-1.aws.neon.tech/neondb"
                        + "?sslmode=require&channel_binding=require");

        assertThat(converted.jdbcUrl())
                .isEqualTo("jdbc:postgresql://ep-cool-name-123.ap-southeast-1.aws.neon.tech/neondb?sslmode=require");
        assertThat(converted.username()).isEqualTo("app_user");
        assertThat(converted.password()).isEqualTo("p@ss+word");
    }

    @Test
    void keepsAnExplicitPort() {
        assertThat(DatabaseUrlEnvironmentPostProcessor.convert("postgres://u:p@localhost:5433/queueless").jdbcUrl())
                .isEqualTo("jdbc:postgresql://localhost:5433/queueless");
    }

    @Test
    void rejectsUnsupportedSchemes() {
        assertThatThrownBy(() -> DatabaseUrlEnvironmentPostProcessor.convert("mysql://u:p@host/db"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void leavesJdbcUrlsAloneAndDoesNotOverrideExplicitCredentials() {
        DatabaseUrlEnvironmentPostProcessor processor = new DatabaseUrlEnvironmentPostProcessor();

        StandardEnvironment jdbcEnvironment = environment(Map.of("spring.datasource.url", "jdbc:postgresql://h/db"));
        processor.postProcessEnvironment(jdbcEnvironment, null);
        assertThat(jdbcEnvironment.getPropertySources().contains(DatabaseUrlEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();

        StandardEnvironment providerEnvironment = environment(Map.of(
                "spring.datasource.url", "postgresql://embedded:secret@h/db",
                "spring.datasource.username", "explicit"));
        processor.postProcessEnvironment(providerEnvironment, null);
        assertThat(providerEnvironment.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://h/db");
        assertThat(providerEnvironment.getProperty("spring.datasource.username")).isEqualTo("explicit");
        assertThat(providerEnvironment.getProperty("spring.datasource.password")).isEqualTo("secret");
    }

    private static StandardEnvironment environment(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment;
    }
}
