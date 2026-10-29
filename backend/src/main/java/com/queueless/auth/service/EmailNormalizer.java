package com.queueless.auth.service;

import java.util.Locale;

/** Emails are compared case-insensitively by storing them trimmed and lower-cased. */
public final class EmailNormalizer {

    private EmailNormalizer() {}

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
