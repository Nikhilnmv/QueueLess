package com.queueless.auth.api;

import com.queueless.auth.service.MembershipDirectory.MembershipSummary;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Request and response contracts of the authentication API. */
public final class AuthDtos {

    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 2, max = 120) String fullName,
            @NotBlank @Size(min = 8, max = 72, message = "must be between 8 and 72 characters") String password) {}

    public record LoginRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(max = 200) String password) {}

    public record CurrentUserResponse(
            UUID id,
            String email,
            String fullName,
            boolean platformAdmin,
            List<MembershipSummary> memberships) {}
}
