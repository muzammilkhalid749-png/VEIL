package com.m3.veil.manager;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Centralized Billing & Quota Gatekeeper for Veil Premium tiers.
 * Enforces free persona limits (Persona 1 [Main] and Persona 2 are Free; Persona 3+ requires Veil Pro).
 */
public class BillingManager {

    private static final String PREFS_NAME = "VeilBillingPrefs";
    private static final String KEY_PREMIUM_ACTIVE = "is_premium_active";
    private static final String KEY_PREMIUM_PLAN = "premium_plan_id";
    private static final String KEY_PREMIUM_EXPIRES_AT = "premium_expires_at";

    public static final int MAX_FREE_PERSONAS = 2;

    public static final String PLAN_MONTHLY = "veil_pro_monthly";
    public static final String PLAN_ANNUAL = "veil_pro_annual";
    public static final String PLAN_LIFETIME = "veil_pro_lifetime";
    public static final String PLAN_COINS_UNLOCK = "veil_pro_coins_unlock";

    public interface BillingStateListener {
        void onPremiumStatusChanged(boolean isPremiumActive, String planId);
    }

    private static volatile BillingManager instance;
    private final Context appContext;
    private final SharedPreferences prefs;
    private final List<BillingStateListener> listeners = new CopyOnWriteArrayList<>();

    private BillingManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static BillingManager getInstance(Context context) {
        if (instance == null) {
            synchronized (BillingManager.class) {
                if (instance == null) {
                    instance = new BillingManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    public static boolean isPremiumActive(Context context) {
        return getInstance(context).isPremiumActive();
    }

    public boolean isPremiumActive() {
        boolean active = prefs.getBoolean(KEY_PREMIUM_ACTIVE, false);
        if (!active) {
            // Also check if unlocked via Persona Master badge in VeilFeaturePrefs
            SharedPreferences featurePrefs = appContext.getSharedPreferences("VeilFeaturePrefs", Context.MODE_PRIVATE);
            String badges = featurePrefs.getString("custom_badges", "");
            if (badges != null && badges.contains("Persona Master")) {
                prefs.edit().putBoolean(KEY_PREMIUM_ACTIVE, true).putString(KEY_PREMIUM_PLAN, PLAN_COINS_UNLOCK).apply();
                return true;
            }
            return false;
        }
        long expiresAt = prefs.getLong(KEY_PREMIUM_EXPIRES_AT, 0L);
        if (expiresAt > 0L && System.currentTimeMillis() > expiresAt) {
            prefs.edit().putBoolean(KEY_PREMIUM_ACTIVE, false).apply();
            return false;
        }
        return true;
    }

    public String getActivePlanId() {
        return prefs.getString(KEY_PREMIUM_PLAN, "");
    }

    public static final int MAX_PERSONAS = 2;

    public boolean hasUsedSecondPersonaSlot() {
        SharedPreferences featurePrefs = appContext.getSharedPreferences("VeilFeaturePrefs", Context.MODE_PRIVATE);
        boolean used = prefs.getBoolean("second_persona_created_ever", false)
                || prefs.getBoolean("second_persona_deleted_ever", false)
                || featurePrefs.getBoolean("second_persona_created_ever", false)
                || featurePrefs.getBoolean("second_persona_deleted_ever", false)
                || featurePrefs.getBoolean("second_persona_deleted", false);
        if (used) return true;
        try {
            int count = PersonaManager.getInstance(appContext).getPersonas().size();
            if (count >= MAX_PERSONAS) {
                markSecondPersonaSlotUsed();
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public boolean hasDeletedSecondPersona() {
        SharedPreferences featurePrefs = appContext.getSharedPreferences("VeilFeaturePrefs", Context.MODE_PRIVATE);
        return prefs.getBoolean("second_persona_deleted_ever", false)
                || featurePrefs.getBoolean("second_persona_deleted_ever", false)
                || featurePrefs.getBoolean("second_persona_deleted", false);
    }

    public void markSecondPersonaSlotUsed() {
        prefs.edit().putBoolean("second_persona_created_ever", true).apply();
        appContext.getSharedPreferences("VeilFeaturePrefs", Context.MODE_PRIVATE)
                .edit().putBoolean("second_persona_created_ever", true).apply();
    }

    public void markSecondPersonaDeleted() {
        prefs.edit()
                .putBoolean("second_persona_created_ever", true)
                .putBoolean("second_persona_deleted_ever", true)
                .apply();
        appContext.getSharedPreferences("VeilFeaturePrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("second_persona_created_ever", true)
                .putBoolean("second_persona_deleted_ever", true)
                .putBoolean("second_persona_deleted", true)
                .apply();
    }

    public boolean canAccessPersonaSlot(int zeroBasedIndex) {
        return zeroBasedIndex < MAX_PERSONAS;
    }

    public boolean canCreateNewPersona(int currentPersonaCount) {
        if (currentPersonaCount >= MAX_PERSONAS) {
            markSecondPersonaSlotUsed();
            return false;
        }
        if (hasUsedSecondPersonaSlot()) {
            return false;
        }
        return currentPersonaCount < MAX_PERSONAS;
    }

    public void activatePremium(String planId, long durationMs) {
        long expiresAt = durationMs > 0 ? (System.currentTimeMillis() + durationMs) : 0L;
        prefs.edit()
                .putBoolean(KEY_PREMIUM_ACTIVE, true)
                .putString(KEY_PREMIUM_PLAN, planId != null ? planId : PLAN_ANNUAL)
                .putLong(KEY_PREMIUM_EXPIRES_AT, expiresAt)
                .apply();
        try {
            VeilFeatureManager.getInstance(appContext).unlockBadge("Persona Master");
            VeilFeatureManager.getInstance(appContext).unlockBadge("Veil Pro");
        } catch (Throwable ignored) {}
        notifyListeners(true, planId);
    }

    public void addListener(BillingStateListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(BillingStateListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void notifyListeners(boolean active, String planId) {
        for (BillingStateListener l : listeners) {
            try {
                l.onPremiumStatusChanged(active, planId);
            } catch (Throwable ignored) {}
        }
    }
}
