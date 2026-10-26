package com.m3.veil.utils;

import androidx.annotation.Nullable;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentSnapshot;

import java.util.Date;

/**
 * Robust utility for reading Firestore DocumentSnapshot fields without throwing
 * ClassCastException or RuntimeException (e.g. Field 'timestamp' is not a java.lang.Number).
 */
public final class VeilFirestoreUtils {

    private VeilFirestoreUtils() {
        // Utility class
    }

    /**
     * Safely parses a Timestamp object from a DocumentSnapshot field.
     * Supports com.google.firebase.Timestamp, java.lang.Number (epoch millis),
     * java.util.Date, or ISO / numeric String representations.
     */
    public static Timestamp getSafeTimestamp(@Nullable DocumentSnapshot doc, String field, @Nullable Timestamp defaultVal) {
        if (doc == null || !doc.contains(field)) {
            return defaultVal != null ? defaultVal : Timestamp.now();
        }
        try {
            Object obj = doc.get(field);
            if (obj instanceof Timestamp) {
                return (Timestamp) obj;
            } else if (obj instanceof Number) {
                return new Timestamp(new Date(((Number) obj).longValue()));
            } else if (obj instanceof Date) {
                return new Timestamp((Date) obj);
            } else if (obj instanceof String) {
                try {
                    long millis = Long.parseLong((String) obj);
                    return new Timestamp(new Date(millis));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            // Guard against any internal Firestore SDK type-cast failures
        }
        return defaultVal != null ? defaultVal : Timestamp.now();
    }

    /**
     * Safely parses a timestamp as epoch milliseconds from a DocumentSnapshot field.
     */
    public static long getSafeTimestampMillis(@Nullable DocumentSnapshot doc, String field, long defaultVal) {
        if (doc == null || !doc.contains(field)) {
            return defaultVal;
        }
        try {
            Object obj = doc.get(field);
            if (obj instanceof Timestamp) {
                return ((Timestamp) obj).toDate().getTime();
            } else if (obj instanceof Number) {
                return ((Number) obj).longValue();
            } else if (obj instanceof Date) {
                return ((Date) obj).getTime();
            } else if (obj instanceof String) {
                try {
                    return Long.parseLong((String) obj);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return defaultVal;
    }

    /**
     * Safely reads a Long/Number from a DocumentSnapshot field.
     */
    public static long getSafeLong(@Nullable DocumentSnapshot doc, String field, long defaultVal) {
        if (doc == null || !doc.contains(field)) {
            return defaultVal;
        }
        try {
            Object obj = doc.get(field);
            if (obj instanceof Number) {
                return ((Number) obj).longValue();
            } else if (obj instanceof String) {
                try {
                    return Long.parseLong((String) obj);
                } catch (Exception ignored) {
                }
            } else if (obj instanceof Timestamp) {
                return ((Timestamp) obj).toDate().getTime();
            }
        } catch (Exception ignored) {
        }
        return defaultVal;
    }

    /**
     * Safely reads a String from a DocumentSnapshot field.
     */
    public static String getSafeString(@Nullable DocumentSnapshot doc, String field, @Nullable String defaultVal) {
        if (doc == null || !doc.contains(field)) {
            return defaultVal;
        }
        try {
            Object obj = doc.get(field);
            if (obj != null) {
                return String.valueOf(obj);
            }
        } catch (Exception ignored) {
        }
        return defaultVal;
    }

    /**
     * Safely reads a Boolean from a DocumentSnapshot field.
     */
    public static boolean getSafeBoolean(@Nullable DocumentSnapshot doc, String field, boolean defaultVal) {
        if (doc == null || !doc.contains(field)) {
            return defaultVal;
        }
        try {
            Object obj = doc.get(field);
            if (obj instanceof Boolean) {
                return (Boolean) obj;
            } else if (obj instanceof String) {
                return Boolean.parseBoolean((String) obj);
            } else if (obj instanceof Number) {
                return ((Number) obj).intValue() != 0;
            }
        } catch (Exception ignored) {
        }
        return defaultVal;
    }
}
