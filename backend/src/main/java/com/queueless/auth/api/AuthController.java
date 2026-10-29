package com.queueless.auth.api;

import com.queueless.auth.api.AuthDtos.CurrentUserResponse;
import com.queueless.auth.api.AuthDtos.LoginRequest;
import com.queueless.auth.api.AuthDtos.RegisterRequest;
import com.queueless.auth.domain.User;
import com.queueless.auth.security.AuthenticatedUser;
import com.queueless.auth.security.SessionAuthenticator;
import com.queueless.auth.service.AttemptLimiter;
import com.queueless.auth.service.AuthService;
import com.queueless.auth.service.CurrentUserService;
import com.queueless.auth.service.EmailNormalizer;
import com.queueless.common.config.QueueLessProperties;
import com.queueless.common.error.ApiException;
import com.queueless.common.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String LOGIN_EMAIL_KEY = "login-email:";
    private static final String LOGIN_IP_KEY = "login-ip:";
    private static final String REGISTER_IP_KEY = "register-ip:";

    private final AuthService authService;
    private final CurrentUserService currentUserService;
    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticator sessionAuthenticator;
    private final AttemptLimiter limiter;
    private final QueueLessProperties.Security limits;

    public AuthController(AuthService authService, CurrentUserService currentUserService,
            AuthenticationManager authenticationManager, SessionAuthenticator sessionAuthenticator,
            AttemptLimiter limiter, QueueLessProperties properties) {
        this.authService = authService;
        this.currentUserService = currentUserService;
        this.authenticationManager = authenticationManager;
        this.sessionAuthenticator = sessionAuthenticator;
        this.limiter = limiter;
        this.limits = properties.security();
    }

    @Operation(summary = "Register a customer account and start a session")
    @PostMapping("/register")
    public ResponseEntity<CurrentUserResponse> register(@Valid @RequestBody RegisterRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        String ipKey = REGISTER_IP_KEY + request.getRemoteAddr();
        limiter.checkNotBlocked(ipKey);
        limiter.recordAttempt(ipKey, limits.registration().maxPerIp(), limits.registration().window(),
                limits.registration().window());

        User user = authService.register(body.email(), body.fullName(), body.password());
        AuthenticatedUser principal = AuthenticatedUser.withoutCredentials(user);
        Authentication authentication =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        sessionAuthenticator.establishSession(authentication, request, response);
        return ResponseEntity.created(URI.create("/api/auth/me")).body(currentUserService.describe(user.getId()));
    }

    @Operation(summary = "Log in with email and password (sets the session cookie)")
    @PostMapping("/login")
    public CurrentUserResponse login(@Valid @RequestBody LoginRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        String email = EmailNormalizer.normalize(body.email());
        String emailKey = LOGIN_EMAIL_KEY + email;
        String ipKey = LOGIN_IP_KEY + request.getRemoteAddr();
        limiter.checkNotBlocked(emailKey);
        limiter.checkNotBlocked(ipKey);

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(email, body.password()));
        } catch (AuthenticationException ex) {
            limiter.recordAttempt(emailKey, limits.login().maxFailuresPerEmail(), limits.login().window(),
                    limits.login().lockout());
            limiter.recordAttempt(ipKey, limits.login().maxFailuresPerIp(), limits.login().window(),
                    limits.login().lockout());
            // Same message for unknown email, wrong password and disabled account (no enumeration).
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
        }
        limiter.reset(emailKey);
        sessionAuthenticator.establishSession(authentication, request, response);
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        return currentUserService.describe(principal.getId());
    }

    @Operation(summary = "Current user and organization memberships")
    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return currentUserService.describe(user.getId());
    }
}
