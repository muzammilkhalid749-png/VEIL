package com.m3.veil.calling;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Durable store for per-peer call safety numbers, mirroring the WhatsApp
 * "security code changed" behaviour.
 *
 * <p>The safety number is derived from the per-call secret, so it necessarily
 * changes for every call. What matters for verification is that the user can
 * mark a peer as verified and be warned when a <em>stored</em> fingerprint is
 * replaced by a different one within the same call-verification session.
 *
 * <p>Fingerprints are not secrets; they are still kept in the app-private
 * preference file and never uploaded.
 */
public final class CallFingerprints {

    public enum Status {
        /** No safety number available yet (E2EE not established). */
        UNKNOWN,
        /** First time we see a fingerprint for this peer. */
        NEW,
        /** Same fingerprint as the stored one. */
        MATCH,
        /** A different fingerprint replaced the stored one. */
        CHANGED
    }

    private static final String PREFS = "veil_call_fingerprints";
    private static final String SUFFIX_VALUE = "_value";
    private static final String SUFFIX_VERIFIED = "_verified";
    private static final String SUFFIX_AT = "_verified_at";

    private CallFingerprints() { }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String base(String peerId) {
        return peerId == null ? "unknown" : peerId;
    }

    /**
     * Records the fingerprint observed for {@code peerId} and reports how it
     * relates to the stored value.
     */
    public static Status record(Context context, String peerId, String safetyNumber) {
        if (context == null || peerId == null || safetyNumber == null || safetyNumber.isEmpty()) {
            return Status.UNKNOWN;
        }
        SharedPreferences p = prefs(context);
        String stored = p.getString(base(peerId) + SUFFIX_VALUE, null);
        if (stored == null) {
            p.edit().putString(base(peerId) + SUFFIX_VALUE, safetyNumber).apply();
            return Status.NEW;
        }
        if (stored.equals(safetyNumber)) {
            return Status.MATCH;
        }
        p.edit()
                .putString(base(peerId) + SUFFIX_VALUE, safetyNumber)
                .remove(base(peerId) + SUFFIX_VERIFIED)
                .remove(base(peerId) + SUFFIX_AT)
                .apply();
        return Status.CHANGED;
    }

    public static String get(Context context, String peerId) {
        if (context == null || peerId == null) return null;
        return prefs(context).getString(base(peerId) + SUFFIX_VALUE, null);
    }

    public static boolean isVerified(Context context, String peerId) {
        if (context == null || peerId == null) return false;
        return prefs(context).getBoolean(base(peerId) + SUFFIX_VERIFIED, false);
    }

    public static void setVerified(Context context, String peerId, boolean verified) {
        if (context == null || peerId == null) return;
        SharedPreferences.Editor editor = prefs(context).edit();
        if (verified) {
            editor.putBoolean(base(peerId) + SUFFIX_VERIFIED, true)
                    .putLong(base(peerId) + SUFFIX_AT, System.currentTimeMillis());
        } else {
            editor.remove(base(peerId) + SUFFIX_VERIFIED).remove(base(peerId) + SUFFIX_AT);
        }
        editor.apply();
    }

    public static long getVerifiedAt(Context context, String peerId) {
        if (context == null || peerId == null) return 0L;
        return prefs(context).getLong(base(peerId) + SUFFIX_AT, 0L);
    }

    public static void clear(Context context, String peerId) {
        if (context == null || peerId == null) return;
        prefs(context).edit()
                .remove(base(peerId) + SUFFIX_VALUE)
                .remove(base(peerId) + SUFFIX_VERIFIED)
                .remove(base(peerId) + SUFFIX_AT)
                .apply();
    }
}
