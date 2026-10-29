package com.queueless.membership.domain;

/** Organization-scoped roles, ordered by privilege: ADMIN includes STAFF, STAFF includes CUSTOMER. */
public enum MembershipRole {
    CUSTOMER(1),
    STAFF(2),
    ADMIN(3);

    private final int level;

    MembershipRole(int level) {
        this.level = level;
    }

    public boolean includes(MembershipRole required) {
        return level >= required.level;
    }

    public int level() {
        return level;
    }
}
