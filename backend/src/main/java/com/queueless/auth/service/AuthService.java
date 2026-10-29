package com.queueless.auth.service;

import com.queueless.auth.domain.User;
import com.queueless.auth.domain.UserRepository;
import com.queueless.common.error.ApiException;
import com.queueless.common.error.ErrorCode;
import com.queueless.common.time.TimeProvider;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    /** bcrypt only uses the first 72 bytes of a password. */
    static final int MAX_PASSWORD_BYTES = 72;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TimeProvider time;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TimeProvider time) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.time = time;
    }

    /**
     * Creates a customer account. Public registration never grants STAFF/ADMIN or platform
     * roles: those come only from memberships created by an organization administrator.
     */
    @Transactional
    public User register(String email, String fullName, String rawPassword) {
        String normalizedEmail = EmailNormalizer.normalize(email);
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Password must be at most 72 bytes long");
        }
        if (users.existsByEmail(normalizedEmail)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED, "An account with this email already exists");
        }
        User user = new User(normalizedEmail, fullName.trim(), passwordEncoder.encode(rawPassword), time.now());
        // Flush now so a concurrent registration of the same email fails inside this call
        // (uq_users_email) and is reported as 409 rather than a commit-time error.
        return users.saveAndFlush(user);
    }
}
