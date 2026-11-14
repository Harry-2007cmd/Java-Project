package com.campus.parking.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.HexFormat;

/**
 * Password hashing helper. Uses PBKDF2WithHmacSHA256 (standard JDK crypto,
 * no extra dependency) with a random salt per user, so plain-text passwords
 * are never stored and two users with the same password don't end up with
 * the same hash.
 */
public final class AuthUtil {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 65536;
    private static final int KEY_LENGTH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AuthUtil() {
    }

    /** Generates a new random salt (as a hex string) for a new user. */
    public static String newSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return HexFormat.of().formatHex(salt);
    }

    /** Derives the password hash (as a hex string) for the given password and salt. */
    public static String hash(String password, String saltHex) {
        try {
            byte[] salt = HexFormat.of().parseHex(saltHex);
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            byte[] hash = factory.generateSecret(spec).getEncoded();
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Could not hash password", e);
        }
    }

    /** Checks a password attempt against the stored salt + hash. */
    public static boolean matches(String passwordAttempt, String saltHex, String expectedHashHex) {
        String attemptHash = hash(passwordAttempt, saltHex);
        return constantTimeEquals(attemptHash, expectedHashHex);
    }

    /** Generates a random session token. */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    // Avoids leaking timing information about how many characters matched.
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
