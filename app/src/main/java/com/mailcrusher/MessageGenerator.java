package com.mailcrusher;

import java.security.SecureRandom;

public final class MessageGenerator {
    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private MessageGenerator() {}

    public static String randomText(int length) {
        if (length < 1 || length > 1000) {
            throw new IllegalArgumentException("Length must be between 1 and 1000.");
        }
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return out.toString();
    }

    public static GeneratedMessage create(int subjectLength, int bodyLength) {
        if (subjectLength < 1 || subjectLength > 120) {
            throw new IllegalArgumentException("Subject length must be 1–120.");
        }
        if (bodyLength < 1 || bodyLength > 1000) {
            throw new IllegalArgumentException("Body length must be 1–1000.");
        }
        return new GeneratedMessage(randomText(subjectLength), randomText(bodyLength));
    }
}
