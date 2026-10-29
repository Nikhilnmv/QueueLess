package com.queueless.membership.api;

import com.queueless.membership.api.MembershipDtos.CreateOrganizationRequest;
import com.queueless.membership.service.OrganizationAdministrationService;
import com.queueless.organization.api.OrganizationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform-level provisioning; the URL rule in SecurityConfig requires ROLE_PLATFORM_ADMIN. */
@Tag(name = "Platform administration")
@RestController
@RequestMapping("/api/platform/organizations")
public class PlatformAdminController {

    private final OrganizationAdministrationService administration;

    public PlatformAdminController(OrganizationAdministrationService administration) {
        this.administration = administration;
    }

    @Operation(summary = "Create an organization with an existing user as its first administrator")
    @PostMapping
    public ResponseEntity<OrganizationResponse> create(@Valid @RequestBody CreateOrganizationRequest body) {
        OrganizationResponse organization = administration.provisionOrganization(body.name(), body.slug(),
                body.description(), body.timezone(), body.adminEmail());
        return ResponseEntity.created(URI.create("/api/organizations/" + organization.slug())).body(organization);
    }
}
