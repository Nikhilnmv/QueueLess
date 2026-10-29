package com.queueless.organization.api;

import com.queueless.organization.service.OrganizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public organization discovery. */
@Tag(name = "Organizations")
@RestController
@RequestMapping("/api/organizations")
public class OrganizationController {

    private final OrganizationService organizations;

    public OrganizationController(OrganizationService organizations) {
        this.organizations = organizations;
    }

    @Operation(summary = "List active organizations, optionally filtered by name")
    @GetMapping
    public List<OrganizationResponse> list(@RequestParam(required = false) String search) {
        return organizations.listPublic(search);
    }

    @Operation(summary = "Public profile of an organization")
    @GetMapping("/{slug}")
    public OrganizationResponse get(@PathVariable String slug) {
        return organizations.getPublic(slug);
    }
}
