package com.queueless.membership.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** The role/permission matrix documented in docs/security.md, as executable expectations. */
class PermissionTest {

    @ParameterizedTest(name = "{0}: customer={1} staff={2} admin={3}")
    @CsvSource({
            "VIEW_QUEUE_BOARD,             false, true,  true",
            "OPERATE_QUEUE,                false, true,  true",
            "REGISTER_GUEST_WALK_IN,       false, true,  true",
            "CHANGE_TICKET_PRIORITY,       false, true,  true",
            "MANAGE_CUSTOMER_BOOKINGS,     false, true,  true",
            "VIEW_ORGANIZATION_MANAGEMENT, false, true,  true",
            "CONFIGURE_QUEUES,             false, false, true",
            "MANAGE_MEMBERS,               false, false, true",
            "UPDATE_ORGANIZATION,          false, false, true"})
    void matrix(Permission permission, boolean customer, boolean staff, boolean admin) {
        assertThat(permission.isGrantedTo(MembershipRole.CUSTOMER)).isEqualTo(customer);
        assertThat(permission.isGrantedTo(MembershipRole.STAFF)).isEqualTo(staff);
        assertThat(permission.isGrantedTo(MembershipRole.ADMIN)).isEqualTo(admin);
    }

    @ParameterizedTest
    @EnumSource(Permission.class)
    void nonMembersHoldNoPermission(Permission permission) {
        assertThat(permission.isGrantedTo(null)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(MembershipRole.class)
    void rolesIncludeLowerRoles(MembershipRole role) {
        assertThat(role.includes(MembershipRole.CUSTOMER)).isTrue();
        assertThat(role.includes(role)).isTrue();
        assertThat(MembershipRole.CUSTOMER.includes(MembershipRole.STAFF)).isFalse();
    }
}
