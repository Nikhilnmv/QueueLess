package com.queueless.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;

/** Turns a successful authentication into a logged-in HTTP session (what form login does internally). */
@Component
public class SessionAuthenticator {

    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository contextRepository;

    public SessionAuthenticator(SessionAuthenticationStrategy sessionStrategy, SecurityContextRepository contextRepository) {
        this.sessionStrategy = sessionStrategy;
        this.contextRepository = contextRepository;
    }

    public void establishSession(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        // Rotates the session id and replaces the CSRF token.
        sessionStrategy.onAuthentication(authentication, request, response);

        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        // Issue the new XSRF-TOKEN cookie on this response so the SPA can send a write next.
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken();
        }
    }
}
