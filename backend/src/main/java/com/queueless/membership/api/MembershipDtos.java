package com.queueless.membership.api;

import com.queueless.membership.domain.MembershipRepository.MemberRow;
import com.queueless.membership.domain.MembershipRole;
import com.queueless.membership.service.MembershipService.MemberCounts;
import com.queueless.organization.api.OrganizationResponse;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class MembershipDtos {

    private MembershipDtos() {}

    public record OrganizationManagementResponse(OrganizationResponse organization, MembershipRole yourRole,
                                                 MemberCounts members) {}

    public record UpdateOrganizationRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description) {}

    public record MemberResponse(UUID membershipId, UUID userId, String email, String fullName,
                                 MembershipRole role, Instant memberSince) {

        public static MemberResponse from(MemberRow row) {
            return new MemberResponse(row.membershipId(), row.userId(), row.email(), row.fullName(), row.role(),
                    row.createdAt());
        }
    }

    public record AddMemberRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotNull MembershipRole role) {}

    public record ChangeRoleRequest(@NotNull MembershipRole role) {}

    public record CreateOrganizationRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(min = 3, max = 60)
            @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$", message = "use lower-case letters, digits and single hyphens")
            String slug,
            @Size(max = 500) String description,
            @NotBlank @Size(max = 64) String timezone,
            @NotBlank @Email @Size(max = 254) String adminEmail) {}
}
