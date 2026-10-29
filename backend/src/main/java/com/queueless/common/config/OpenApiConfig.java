package com.queueless.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI queueLessOpenApi() {
        return new OpenAPI().info(new Info()
                .title("QueueLess API")
                .version("v1")
                .description("""
                        Real-time queue and appointment management. Authentication uses an HTTP-only \
                        session cookie (QUEUELESS_SESSION) created by POST /api/auth/login. Every \
                        state-changing request must send the X-XSRF-TOKEN header with the value of \
                        the XSRF-TOKEN cookie. Errors use RFC 9457 problem details with a 'code' field."""));
    }
}
