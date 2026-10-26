package com.m3.veil.utils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Anonymous Authentication Manager.
 * Delegates recovery phrase generation, normalization, and validation to SovereignIdentityAndDirectoryEngine.
 */
public class AnonymousAuthManager {

    public static String generateRecoveryPhrase() {
        return SovereignIdentityAndDirectoryEngine.getInstance(null).generateMnemonic();
    }

    public static String normalizePhrase(String phrase) {
        return SovereignIdentityAndDirectoryEngine.normalizePhrase(phrase);
    }

    public static boolean validatePhrase(String phrase) {
        return SovereignIdentityAndDirectoryEngine.getInstance(null).validatePhrase(phrase);
    }

    public static String getEmailFromPhrase(String phrase) {
        String clean = normalizePhrase(phrase);
        String hash = sha256(clean);
        return hash.substring(0, 20) + "@veil.local";
    }

    public static String getPasswordFromPhrase(String phrase) {
        String clean = normalizePhrase(phrase);
        return sha256(clean + "_password_salt").substring(0, 20);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
