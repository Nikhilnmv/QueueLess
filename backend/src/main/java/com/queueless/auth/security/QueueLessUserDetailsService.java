package com.queueless.auth.security;

import com.queueless.auth.domain.UserRepository;
import com.queueless.auth.service.EmailNormalizer;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QueueLessUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public QueueLessUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        return users.findByEmail(EmailNormalizer.normalize(username))
                .map(AuthenticatedUser::forAuthentication)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
    }
}
