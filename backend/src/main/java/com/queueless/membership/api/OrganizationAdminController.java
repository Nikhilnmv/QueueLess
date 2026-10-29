package com.queueless.membership.api;

import com.queueless.auth.security.AuthenticatedUser;
import com.queueless.membership.api.MembershipDtos.AddMemberRequest;
import com.queueless.membership.api.MembershipDtos.ChangeRoleRequest;
import com.queueless.membership.api.MembershipDtos.MemberResponse;
import com.queueless.membership.api.MembershipDtos.OrganizationManagementResponse;
import com.queueless.membership.api.MembershipDtos.UpdateOrganizationRequest;
import com.queueless.membership.service.OrganizationAdministrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Organization management for its own STAFF (read) and ADMIN (write) members. */
@Tag(name = "Organization administration")
@RestController
@RequestMapping("/api/admin/organizations/{organizationId}")
public class OrganizationAdminController {

    private final OrganizationAdministrationService administration;

    public OrganizationAdminController(OrganizationAdministrationService administration) {
        this.administration = administration;
    }

    @Operation(summary = "Management overview (STAFF or ADMIN of the organization)")
    @GetMapping
    public OrganizationManagementResponse overview(@PathVariable UUID organizationId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return administration.overview(organizationId, user.getId());
    }

    @Operation(summary = "Update name and description (ADMIN)")
    @PatchMapping
    public OrganizationManagementResponse update(@PathVariable UUID organizationId,
            @Valid @RequestBody UpdateOrganizationRequest body, @AuthenticationPrincipal AuthenticatedUser user) {
        return administration.updateProfile(organizationId, user.getId(), body.name(), body.description());
    }

    @Operation(summary = "List members (ADMIN)")
    @GetMapping("/members")
    public List<MemberResponse> members(@PathVariable UUID organizationId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return administration.members(organizationId, user.getId()).stream().map(MemberResponse::from).toList();
    }

    @Operation(summary = "Add a registered user as CUSTOMER, STAFF or ADMIN (ADMIN)")
    @PostMapping("/members")
    public ResponseEntity<MemberResponse> addMember(@PathVariable UUID organizationId,
            @Valid @RequestBody AddMemberRequest body, @AuthenticationPrincipal AuthenticatedUser user) {
        MemberResponse member = MemberResponse.from(
                administration.addMember(organizationId, user.getId(), body.email(), body.role()));
        return ResponseEntity.created(URI.create("/api/admin/organizations/" + organizationId + "/members/"
                + member.membershipId())).body(member);
    }

    @Operation(summary = "Change a member's role (ADMIN; the last admin cannot be demoted)")
    @PatchMapping("/members/{membershipId}")
    public MemberResponse changeRole(@PathVariable UUID organizationId, @PathVariable UUID membershipId,
            @Valid @RequestBody ChangeRoleRequest body, @AuthenticationPrincipal AuthenticatedUser user) {
        return MemberResponse.from(administration.changeRole(organizationId, membershipId, user.getId(), body.role()));
    }

    @Operation(summary = "Remove a member (ADMIN; the last admin cannot be removed)")
    @DeleteMapping("/members/{membershipId}")
    public ResponseEntity<Void> removeMember(@PathVariable UUID organizationId, @PathVariable UUID membershipId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        administration.removeMember(organizationId, membershipId, user.getId());
        return ResponseEntity.noContent().build();
    }
}
