package com.queueless.auth.security;

import com.queueless.common.config.QueueLessProperties;
import com.queueless.common.error.ErrorCode;
import com.queueless.common.error.ProblemResponseWriter;
import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * HTTP security: session-cookie authentication, SPA-friendly CSRF protection and coarse URL rules.
 *
 * <p>URL rules only distinguish public, authenticated and platform-admin endpoints. Organization
 * scoping (is this user STAFF of <em>this</em> queue's organization?) cannot be expressed as a URL
 * pattern, so every service method checks it through {@code AccessControlService}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data:",
            "font-src 'self' data:",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            CsrfTokenRepository csrfTokenRepository,
                                            SecurityContextRepository securityContextRepository,
                                            ProblemResponseWriter problems,
                                            ServerProperties serverProperties,
                                            QueueLessProperties properties) throws Exception {
        String sessionCookieName = serverProperties.getServlet().getSession().getCookie().getName();

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository)
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
            .securityContext(context -> context.securityContextRepository(securityContextRepository))
            .requestCache(RequestCacheConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                .deleteCookies(sessionCookieName))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, ex) ->
                    problems.write(request, response, ErrorCode.AUTHENTICATION_REQUIRED, "Log in to continue"))
                .accessDeniedHandler((request, response, ex) -> {
                    if (ex instanceof CsrfException) {
                        problems.write(request, response, ErrorCode.CSRF_REJECTED,
                            "Send the X-XSRF-TOKEN header with the value of the XSRF-TOKEN cookie");
                    } else {
                        problems.write(request, response, ErrorCode.ACCESS_DENIED,
                            "You do not have permission to perform this action");
                    }
                }))
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .frameOptions(frame -> frame.deny()))
            .authorizeHttpRequests(auth -> auth
                // Completion of SSE responses and error rendering re-enter the filter chain.
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                // Public discovery data (no customer information).
                .requestMatchers(HttpMethod.GET,
                    "/api/organizations", "/api/organizations/*", "/api/organizations/*/queues",
                    "/api/queues/*", "/api/queues/*/slots", "/api/queues/*/live").permitAll()
                .requestMatchers("/api/platform/**").hasRole("PLATFORM_ADMIN")
                .requestMatchers("/api/**").authenticated()
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/actuator/**").denyAll()
                // React application shell, static assets and API documentation.
                .anyRequest().permitAll());

        if (properties.security().httpsRedirect()) {
            // Only requests that reached the platform proxy over plain HTTP are redirected; internal
            // health checks (no X-Forwarded-Proto header) are left alone.
            http.redirectToHttps(https -> https.requestMatchers(
                request -> "http".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"))));
        }
        return http.build();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository(ServerProperties serverProperties) {
        Boolean secure = serverProperties.getServlet().getSession().getCookie().getSecure();
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie
            .path("/")
            .sameSite("Lax")
            .secure(Boolean.TRUE.equals(secure)));
        return repository;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
            new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
    }

    /** New session id on login (session fixation protection) and a fresh CSRF token. */
    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrfTokenRepository) {
        return new CompositeSessionAuthenticationStrategy(List.of(
            new ChangeSessionIdAuthenticationStrategy(),
            new CsrfAuthenticationStrategy(csrfTokenRepository)));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // bcrypt by default, with an {id} prefix so the algorithm can be upgraded later.
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
