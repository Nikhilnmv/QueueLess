package com.queueless.membership.domain;

/**
 * Organization-scoped permissions and the minimum membership role that grants each one.
 * This enum is the code form of the permission matrix in docs/security.md.
 *
 * <p>Customer self-service (booking, joining a queue, cancelling one's own ticket) needs no
 * membership: it is authorized by resource ownership instead.
 */
public enum Permission {
    VIEW_QUEUE_BOARD(MembershipRole.STAFF, "view the live queue board"),
    OPERATE_QUEUE(MembershipRole.STAFF, "operate the queue"),
    REGISTER_GUEST_WALK_IN(MembershipRole.STAFF, "register walk-in customers"),
    CHANGE_TICKET_PRIORITY(MembershipRole.STAFF, "change ticket priority"),
    MANAGE_CUSTOMER_BOOKINGS(MembershipRole.STAFF, "manage customer bookings"),
    VIEW_ORGANIZATION_MANAGEMENT(MembershipRole.STAFF, "view organization management data"),
    CONFIGURE_QUEUES(MembershipRole.ADMIN, "configure queues"),
    MANAGE_MEMBERS(MembershipRole.ADMIN, "manage members"),
    UPDATE_ORGANIZATION(MembershipRole.ADMIN, "update the organization profile");

    private final MembershipRole minimumRole;
    private final String description;

    Permission(MembershipRole minimumRole, String description) {
        this.minimumRole = minimumRole;
        this.description = description;
    }

    /** The single permission decision used everywhere. {@code role} is null for non-members. */
    public boolean isGrantedTo(MembershipRole role) {
        return role != null && role.includes(minimumRole);
    }

    public MembershipRole minimumRole() {
        return minimumRole;
    }

    public String description() {
        return description;
    }
}
