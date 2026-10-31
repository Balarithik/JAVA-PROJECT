package com.smartqueue.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class PasswordHasher {
    private static final String ALGORITHM = "pbkdf2_sha256";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int ITERATIONS = 600_000;
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int SALT_LENGTH = 22;
    private static final int MAX_VERIFICATION_ITERATIONS = 5_000_000;
    private static final char[] SALT_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    public static String hash(String password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Password must not be empty.");
        }

        String salt = generateSalt();
        byte[] digest = derive(password, salt, ITERATIONS);
        return ALGORITHM + "$" + ITERATIONS + "$" + salt + "$"
                + Base64.getEncoder().encodeToString(digest);
    }

    public static boolean verify(String password, String encoded) {
        if (password == null || encoded == null) {
            return false;
        }

        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 4 || !ALGORITHM.equals(parts[0])
                || parts[2].isEmpty() || parts[2].length() > 64) {
            return false;
        }

        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 1 || iterations > MAX_VERIFICATION_ITERATIONS) {
                return false;
            }
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(password, parts[2], iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String generateSalt() {
        char[] salt = new char[SALT_LENGTH];
        for (int i = 0; i < salt.length; i++) {
            salt[i] = SALT_ALPHABET[RANDOM.nextInt(SALT_ALPHABET.length)];
        }
        return new String(salt);
    }

    private static byte[] derive(String password, String salt, int iterations) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] saltBytes = salt.getBytes(StandardCharsets.UTF_8);
            byte[] saltBlock = new byte[saltBytes.length + 4];
            System.arraycopy(saltBytes, 0, saltBlock, 0, saltBytes.length);
            saltBlock[saltBlock.length - 1] = 1;
            byte[] u = mac.doFinal(saltBlock);
            byte[] result = u.clone();

            for (int i = 1; i < iterations; i++) {
                u = mac.doFinal(u);
                for (int j = 0; j < result.length; j++) {
                    result[j] ^= u[j];
                }
            }
            return result;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("PBKDF2-SHA256 is unavailable.", ex);
        }
    }
}