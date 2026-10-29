package com.queueless.auth.service;

import com.queueless.auth.api.AuthDtos.CurrentUserResponse;
import com.queueless.auth.domain.User;
import com.queueless.auth.domain.UserRepository;
import com.queueless.common.error.ApiException;
import com.queueless.common.error.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CurrentUserService {

    private final UserRepository users;
    private final MembershipDirectory memberships;

    public CurrentUserService(UserRepository users, MembershipDirectory memberships) {
        this.users = users;
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public CurrentUserResponse describe(UUID userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "Log in to continue"));
        return new CurrentUserResponse(user.getId(), user.getEmail(), user.getFullName(), user.isPlatformAdmin(),
                memberships.membershipsOf(userId));
    }
}
