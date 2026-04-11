package com.linearlite.server.util;

import java.util.regex.Pattern;

public final class EmailNormalization {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");

    private EmailNormalization() {
    }

    public static String normalizeAndValidate(String email, String requiredMessage, String invalidMessage) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException(requiredMessage);
        }
        String normalized = email.trim().toLowerCase();
        if (!EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(invalidMessage);
        }
        return normalized;
    }
}
