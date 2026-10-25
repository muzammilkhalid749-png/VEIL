package com.m3.veil.manager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class PremiumUsernameManager {

    // Reserved usernames for branding and partnerships
    private static final Set<String> RESERVED_USERNAMES = new HashSet<>(Arrays.asList(
            // App branding - completely reserved
            "veil", "veilchat", "officialveil", "veil123", "veilapp", "getveil", "veilmessenger",
            "veilteam", "veilsupport", "veilhelp", "veiladmin", "veilofficial", "veilinc",
            "veilcorp", "veiltech", "veilsecure", "veilprivacy", "veilprivate", "veilmessage",
            "veilmsg", "veilchatting", "veilcommunication", "veilappofficial",

            // Common brand names
            "amazon", "amazon23", "netflix", "netflixhelp", "google", "microsoft", "apple",
            "meta", "facebook", "instagram", "whatsapp", "telegram", "signal", "twitter",
            "x", "youtube", "spotify", "tiktok", "snapchat",

            // Generic premium names
            "admin", "support", "help", "contact", "info", "official", "team", "ceo", "founder",
            "boss", "king", "queen", "star", "pro", "expert", "guru", "master", "legend",
            "elite", "premium", "vip", "gold", "diamond", "prime", "ultimate",

            // Single characters and short names
            "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q",
            "r", "s", "t", "u", "v", "w", "x", "y", "z",
            "aa", "ab", "ac", "ad", "ae", "af", "ag", "ah", "ai", "aj", "ak", "al", "am", "an",
            "ao", "ap", "aq", "ar", "as", "at", "au", "av", "aw", "ax", "ay", "az"
    ));

    // VEIL keyword variations that are not allowed
    private static final Set<String> VEIL_KEYWORD_VARIANTS = new HashSet<>(Arrays.asList(
            "veil", "veils", "veiled", "veiling", "veilz", "veilx", "veil0", "veil1", "veil2",
            "veil3", "veil4", "veil5", "veil6", "veil7", "veil8", "veil9", "vveil", "veill",
            "viel", "viele", "veiluser", "veilmaster", "veilpro", "veilguy", "veilgirl",
            "veilboy", "veilman", "veilwoman", "veillover", "veilfan", "veilking", "veilqueen",
            "veilboss", "veilgod", "veillord", "veilhero", "veilstar", "veillegend"
    ));

    // Premium username patterns (regex)
    private static final String[] PREMIUM_PATTERNS = {
            "^[a-z]{1,3}$", // 1-3 letter usernames
            "^[a-z]+[0-9]{0,2}$", // clean names with 0-2 numbers
            "^[a-z]+\\.?[a-z]+$", // names with dots
            "^[a-z]+_[a-z]+$" // names with underscores
    };

    public static boolean isPremiumUsername(String username) {
        String cleanUsername = username.toLowerCase().trim();

        // Check reserved list
        if (RESERVED_USERNAMES.contains(cleanUsername)) {
            return true;
        }

        // Check premium patterns
        for (String pattern : PREMIUM_PATTERNS) {
            if (cleanUsername.matches(pattern)) {
                return true;
            }
        }

        // Check length-based premium (very short usernames)
        if (cleanUsername.length() <= 4) {
            return true;
        }

        return false;
    }

    public static boolean isReservedUsername(String username) {
        return RESERVED_USERNAMES.contains(username.toLowerCase().trim());
    }

    public static boolean containsVeilKeyword(String username) {
        String cleanUsername = username.toLowerCase().trim();

        // Exact match with VEIL variants
        if (VEIL_KEYWORD_VARIANTS.contains(cleanUsername)) {
            return true;
        }

        // Contains "veil" as a substring (with word boundaries)
        if (cleanUsername.matches(".*\\bveil\\b.*")) {
            return true;
        }

        // Common veil combinations
        if (cleanUsername.matches(".*veil[0-9].*") ||
                cleanUsername.matches(".*veil_.*") ||
                cleanUsername.matches(".*_veil.*") ||
                cleanUsername.matches(".*veilx.*") ||
                cleanUsername.matches(".*veilz.*")) {
            return true;
        }

        // Common misspellings and variations
        String[] veilPatterns = {
                ".*v[3e]il.*", // v3il, veil
                ".*v[e3]il.*", // veil, v3il
                ".*[vw]eil.*", // veil, weil
                ".*ve[i1]l.*", // veil, ve1l
                ".*vei[l1].*"  // veil, vei1
        };

        for (String pattern : veilPatterns) {
            if (cleanUsername.matches(pattern)) {
                return true;
            }
        }

        return false;
    }

    public static String getPremiumUsernameMessage(String username) {
        if (isReservedUsername(username)) {
            return "This username is reserved for official use";
        } else if (containsVeilKeyword(username)) {
            return "This username contains protected brand keywords";
        } else if (username.length() <= 3) {
            return "Short usernames (1-3 chars) are premium";
        } else if (username.length() <= 4) {
            return "4-character usernames are premium";
        } else {
            return "This username is available as a premium feature";
        }
    }

    public static String getVeilRestrictionMessage() {
        return "Usernames containing 'veil' or similar variations are not allowed to protect our brand identity.";
    }
}