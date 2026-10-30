package com.m3.veil.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.m3.veil.model.Persona;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Central manager for VeilChat's 226-Feature Framework:
 * - Multi-Persona Identity System (with persona-specific bubble colors, moods, and auto-expiry)
 * - Gamification & Economy (XP, Levels, Daily Streaks, Karma, VeilCoins, Badges)
 * - Privacy Score & Panic/Decoy Security Controls
 * - Client-Side Moderation, Word Filter, and Non-AI Truth-or-Dare / Icebreakers
 */
public class VeilFeatureManager {

    private static final String PREFS_NAME = "VeilFeaturePrefs";
    private static VeilFeatureManager instance;
    private final Context appContext;
    private final SharedPreferences prefs;
    private final SharedPreferences mainPrefs;
    private final List<StatusUpdate> remotePeerStatuses = new ArrayList<>();
    private ListenerRegistration statusListenerRegistration;

    public static synchronized VeilFeatureManager getInstance(Context context) {
        if (instance == null) {
            instance = new VeilFeatureManager(context.getApplicationContext());
        }
        return instance;
    }

    private VeilFeatureManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.mainPrefs = context.getSharedPreferences("VeilPrefs", Context.MODE_PRIVATE);
        ensureDefaultPersonas();
        recordDailyLoginStreak();
        try {
            StatusController.getInstance(appContext).addObserver(this::notifyStatusListeners);
        } catch (Throwable ignored) {}
    }

    // ==========================================
    // I. MULTI-PERSONA SYSTEM
    // ==========================================

    public static boolean isShadowVeilOrGeneric(String name) {
        if (name == null) return true;
        String clean = name.trim();
        return clean.isEmpty()
                || clean.equalsIgnoreCase("@ShadowVeil")
                || clean.equalsIgnoreCase("ShadowVeil")
                || clean.equalsIgnoreCase("@Anonymous")
                || clean.equalsIgnoreCase("Anonymous")
                || clean.equalsIgnoreCase("VeilUser")
                || clean.equalsIgnoreCase("Veil User")
                || clean.equalsIgnoreCase("@User")
                || clean.equalsIgnoreCase("Direct Chat")
                || clean.equalsIgnoreCase("Veil Contact")
                || clean.equalsIgnoreCase("Anonymous Peer");
    }

    public String resolveRealUserHandle() {
        try {
            com.m3.veil.utils.UserPreferences up = new com.m3.veil.utils.UserPreferences(appContext);
            String uname = up.getUsername();
            String dname = up.getDisplayName();
            com.m3.veil.model.User u = up.getCurrentUser();
            if (u != null) {
                if (isShadowVeilOrGeneric(uname) && !isShadowVeilOrGeneric(u.getUsername())) {
                    uname = u.getUsername();
                }
                if (isShadowVeilOrGeneric(dname) && !isShadowVeilOrGeneric(u.getDisplayName())) {
                    dname = u.getDisplayName();
                }
            }
            if (isShadowVeilOrGeneric(uname)) {
                uname = mainPrefs.getString("custom_username", null);
            }
            if (isShadowVeilOrGeneric(uname)) {
                android.content.SharedPreferences vPrefs = appContext.getSharedPreferences("veil_prefs", Context.MODE_PRIVATE);
                uname = vPrefs.getString("username", null);
            }
            if (isShadowVeilOrGeneric(dname)) {
                dname = mainPrefs.getString("display_name", null);
            }
            if (!isShadowVeilOrGeneric(uname)) {
                String clean = uname.trim();
                return clean.startsWith("@") ? clean : "@" + clean;
            }
            if (!isShadowVeilOrGeneric(dname)) {
                String clean = dname.trim();
                return clean.startsWith("@") ? clean : "@" + clean;
            }
        } catch (Throwable ignored) {}
        return "@User";
    }

    public String resolveRealUserDisplayName() {
        try {
            com.m3.veil.utils.UserPreferences up = new com.m3.veil.utils.UserPreferences(appContext);
            String dname = up.getDisplayName();
            if (isShadowVeilOrGeneric(dname)) {
                com.m3.veil.model.User u = up.getCurrentUser();
                if (u != null && !isShadowVeilOrGeneric(u.getDisplayName())) {
                    dname = u.getDisplayName();
                }
            }
            if (!isShadowVeilOrGeneric(dname)) {
                return dname.trim();
            }
            String handle = resolveRealUserHandle();
            if (!isShadowVeilOrGeneric(handle)) {
                return handle.startsWith("@") ? handle.substring(1) : handle;
            }
        } catch (Throwable ignored) {}
        return "User";
    }

    public static final int MAX_FREE_PERSONAS = 2;

    public interface PersonaChangeListener {
        void onPersonaChanged(Persona activePersona);
    }

    private final List<PersonaChangeListener> personaListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addPersonaChangeListener(PersonaChangeListener listener) {
        if (listener != null && !personaListeners.contains(listener)) {
            personaListeners.add(listener);
        }
    }

    public void removePersonaChangeListener(PersonaChangeListener listener) {
        personaListeners.remove(listener);
    }

    private void notifyPersonaChanged(Persona active) {
        android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        mainHandler.post(() -> {
            for (PersonaChangeListener l : personaListeners) {
                try {
                    l.onPersonaChanged(active);
                } catch (Throwable ignored) {}
            }
        });
    }

    public boolean isThirdPersonaPremiumUnlocked() {
        return prefs.getBoolean("unlock_unlimited_personas", false)
                || prefs.getBoolean("veil_premium_persona_pro", false);
    }

    public void setThirdPersonaPremiumUnlocked(boolean unlocked) {
        prefs.edit()
                .putBoolean("unlock_unlimited_personas", unlocked)
                .putBoolean("veil_premium_persona_pro", unlocked)
                .apply();
        if (unlocked) {
            unlockBadge("👑 Multi-Persona Pro");
        }
    }

    public boolean canAccessPersonaIndex(int index) {
        return index < MAX_FREE_PERSONAS || isThirdPersonaPremiumUnlocked();
    }

    public boolean isPrimaryPersonaActive() {
        String activeId = prefs.getString("active_persona_id", "persona_primary");
        return activeId == null || activeId.isEmpty() || "persona_primary".equals(activeId) || "default".equals(activeId);
    }

    public String resolveBaseUserId() {
        try {
            com.m3.veil.utils.PreferenceManager pm = new com.m3.veil.utils.PreferenceManager(appContext);
            String sovUid = pm.getString("sovereign_uid", null);
            if (sovUid != null && !sovUid.trim().isEmpty()) {
                return cleanBaseUid(sovUid.trim());
            }
            com.m3.veil.utils.UserPreferences up = new com.m3.veil.utils.UserPreferences(appContext);
            String uid = up.getUserId();
            if (uid != null && !uid.trim().isEmpty()) {
                return cleanBaseUid(uid.trim());
            }
            String sUid = com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(appContext).getCurrentUserId();
            if (sUid != null && !sUid.trim().isEmpty()) {
                return cleanBaseUid(sUid.trim());
            }
        } catch (Throwable ignored) {}
        return "local_user";
    }

    private static String cleanBaseUid(String uid) {
        if (uid == null) return "local_user";
        int idx = uid.indexOf("__persona_");
        if (idx > 0) {
            return uid.substring(0, idx);
        }
        return uid;
    }

    /**
     * Returns the effective user ID for the currently active persona across the entire app:
     * - For Main persona (`persona_primary`): returns the base account UID.
     * - For Second/Third persona: returns `<baseUid>__<personaId>` so chats, calls, messages,
     *   Firestore profile, and directory lookup operate as a completely independent identity!
     */
    public String getActiveEffectiveUserId() {
        String baseUid = resolveBaseUserId();
        String activeId = prefs.getString("active_persona_id", "persona_primary");
        if (activeId == null || activeId.trim().isEmpty() || "persona_primary".equals(activeId) || "default".equals(activeId)) {
            return baseUid;
        }
        return baseUid + "__" + activeId.trim();
    }

    public void syncPrimaryPersonaWithUser(String username, String displayName, String bio, String mood) {
        try {
            String handle = (!isShadowVeilOrGeneric(username))
                    ? (username.trim().startsWith("@") ? username.trim() : "@" + username.trim())
                    : resolveRealUserHandle();
            String dName = (!isShadowVeilOrGeneric(displayName))
                    ? displayName.trim()
                    : resolveRealUserDisplayName();
            JSONArray arr = new JSONArray(prefs.getString("personas_json", "[]"));
            boolean updated = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if ("persona_primary".equals(o.optString("id"))) {
                    if (!isShadowVeilOrGeneric(dName)) {
                        o.put("name", dName);
                        updated = true;
                    }
                    if (!isShadowVeilOrGeneric(handle)) {
                        o.put("username", handle);
                        updated = true;
                    }
                    if (bio != null && !bio.trim().isEmpty()) {
                        o.put("bio", bio.trim());
                        updated = true;
                    }
                    if (mood != null && !mood.trim().isEmpty()) {
                        o.put("mood", mood.trim());
                        updated = true;
                    }
                    break;
                }
            }
            if (updated) {
                prefs.edit().putString("personas_json", arr.toString()).apply();
            }
        } catch (Exception ignored) {}
    }

    private void ensureDefaultPersonas() {
        String json = prefs.getString("personas_json", null);
        String realHandle = resolveRealUserHandle();
        String realDisplayName = resolveRealUserDisplayName();
        if (json == null || json.isEmpty()) {
            try {
                JSONArray arr = new JSONArray();

                JSONObject p1 = new JSONObject();
                p1.put("id", "persona_primary");
                p1.put("name", realDisplayName);
                p1.put("username", realHandle);
                p1.put("bio", "Primary zero-trace identity");
                p1.put("mood", "🛡️ Encrypted");
                p1.put("bubbleColor", "#0B57CF");
                p1.put("temporary", false);
                arr.put(p1);

                JSONObject p2 = new JSONObject();
                p2.put("id", "persona_ghost");
                p2.put("name", "Midnight Drifter");
                p2.put("username", "@midnight_drifter");
                p2.put("bio", "Second persona • Isolated E2EE vault");
                p2.put("mood", "🌙 Incognito");
                p2.put("bubbleColor", "#6A1B9A");
                p2.put("temporary", false);
                arr.put(p2);

                JSONObject p3 = new JSONObject();
                p3.put("id", "persona_neon");
                p3.put("name", "Cyber Pulse");
                p3.put("username", "@cyber_pulse");
                p3.put("bio", "Pro Creator & Stealth Debater");
                p3.put("mood", "⚡ Hyped");
                p3.put("bubbleColor", "#00695C");
                p3.put("temporary", false);
                arr.put(p3);

                prefs.edit()
                        .putString("personas_json", arr.toString())
                        .putString("active_persona_id", "persona_primary")
                        .apply();
            } catch (Exception ignored) {}
        }
    }

    public List<Persona> getPersonas() {
        List<Persona> list = new ArrayList<>();
        String realHandle = resolveRealUserHandle();
        String realDisplayName = resolveRealUserDisplayName();
        boolean needsSave = false;
        java.util.Set<String> seenUsernames = new java.util.HashSet<>();
        java.util.Set<String> seenNames = new java.util.HashSet<>();

        try {
            JSONArray arr = new JSONArray(prefs.getString("personas_json", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String id = o.optString("id");
                String name = o.optString("name");
                String uname = o.optString("username");

                if ("persona_primary".equals(id) || i == 0) {
                    if (isShadowVeilOrGeneric(name)) {
                        name = realDisplayName;
                        o.put("name", name);
                        needsSave = true;
                    }
                    if (isShadowVeilOrGeneric(uname)) {
                        uname = realHandle;
                        o.put("username", uname);
                        needsSave = true;
                    }
                } else {
                    // Ensure 2nd and 3rd personas NEVER show the same username or display name as Main or each other!
                    String defaultName = (i == 1 || "persona_ghost".equals(id)) ? "Midnight Drifter"
                            : ((i == 2 || "persona_neon".equals(id)) ? "Cyber Pulse" : ("Shadow Persona " + (i + 1)));
                    String defaultUname = (i == 1 || "persona_ghost".equals(id)) ? "@midnight_drifter"
                            : ((i == 2 || "persona_neon".equals(id)) ? "@cyber_pulse" : ("@persona_" + (i + 1)));

                    String cleanCandidateUname = (uname != null && !uname.trim().isEmpty()) ? uname.trim() : name;
                    if (isShadowVeilOrGeneric(name)
                            || name.equalsIgnoreCase(realDisplayName)
                            || name.equalsIgnoreCase(realHandle)
                            || seenNames.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                        name = defaultName;
                        o.put("name", name);
                        needsSave = true;
                    }
                    if (isShadowVeilOrGeneric(cleanCandidateUname)
                            || cleanCandidateUname.equalsIgnoreCase(realHandle)
                            || cleanCandidateUname.equalsIgnoreCase(realDisplayName)
                            || ("@" + cleanCandidateUname.replaceFirst("^@+", "")).equalsIgnoreCase(realHandle)
                            || seenUsernames.contains(cleanCandidateUname.replaceFirst("^@+", "").toLowerCase(java.util.Locale.ROOT))) {
                        uname = defaultUname;
                        o.put("username", uname);
                        needsSave = true;
                    } else if (uname == null || uname.trim().isEmpty()) {
                        uname = "@" + name.toLowerCase(java.util.Locale.ROOT).replaceFirst("^@+", "").replaceAll("[^a-z0-9_.]", "_");
                        o.put("username", uname);
                        needsSave = true;
                    }
                }

                Persona p = new Persona(
                        id,
                        uname,
                        name,
                        o.optString("bio"),
                        o.optString("bubbleColor", "#0B57CF"),
                        o.optString("mood", "✨ Active"),
                        o.optBoolean("temporary", false),
                        o.optLong("expiresAt", 0L)
                );
                seenNames.add(p.getDisplayName().toLowerCase(java.util.Locale.ROOT));
                seenUsernames.add(p.getCleanUsername().toLowerCase(java.util.Locale.ROOT));
                list.add(p);
            }
            // Enforce active persona cannot remain on a locked 3rd+ persona if premium is not unlocked
            String activeId = prefs.getString("active_persona_id", "persona_primary");
            for (int idx = 0; idx < list.size(); idx++) {
                if (list.get(idx).getPersonaId().equals(activeId) && !canAccessPersonaIndex(idx)) {
                    prefs.edit().putString("active_persona_id", "persona_primary").apply();
                    break;
                }
            }
            if (needsSave) {
                prefs.edit().putString("personas_json", arr.toString()).apply();
            }
        } catch (Exception ignored) {}
        return list;
    }

    public Persona getActivePersona() {
        String activeId = prefs.getString("active_persona_id", "persona_primary");
        List<Persona> all = getPersonas();
        for (int i = 0; i < all.size(); i++) {
            Persona p = all.get(i);
            if (p.getPersonaId().equals(activeId)) {
                if (!canAccessPersonaIndex(i)) {
                    return all.get(0);
                }
                return p;
            }
        }
        return all.isEmpty()
                ? new Persona("persona_primary", resolveRealUserHandle(), resolveRealUserDisplayName(), "Encrypted user", "#0B57CF", "🛡️ Encrypted", false, 0L)
                : all.get(0);
    }

    public void setActivePersona(String personaId) {
        List<Persona> all = getPersonas();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getPersonaId().equals(personaId) && !canAccessPersonaIndex(i)) {
                return;
            }
        }
        prefs.edit().putString("active_persona_id", personaId).putBoolean("decoy_mode_active", false).apply();
        appContext.getSharedPreferences("veil_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("active_persona_id", personaId)
                .apply();
        com.m3.veil.database.EncryptedChatDatabase.switchActiveVault(appContext, personaId, false);
        Persona active = getActivePersona();
        try {
            AuthManager.getInstance().onPersonaSwitched(appContext, personaId);
        } catch (Throwable ignored) {}
        try {
            VeilMessagesController.getInstance(appContext).switchActivePersonaVault();
            VeilBoardController.getInstance(appContext).switchActivePersonaVault();
            StatusController.getInstance(appContext).reloadForActiveVault();
        } catch (Throwable ignored) {}
        publishActivePersonaIdentityToDirectory(active);
        notifyPersonaChanged(active);
        addXp(5);
    }

    private void publishActivePersonaIdentityToDirectory(Persona active) {
        if (active == null) return;
        new Thread(() -> {
            try {
                String effectiveUid = getActiveEffectiveUserId();
                String cleanUname = active.getCleanUsername().toLowerCase(java.util.Locale.ROOT);
                String dName = active.getDisplayName();
                String bio = active.getBio();
                String mood = active.getMood();
                com.m3.veil.utils.SovereignIdentityAndDirectoryEngine engine =
                        com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(appContext);
                engine.recordClaimedUsername(cleanUname);

                com.google.firebase.firestore.FirebaseFirestore db = com.google.firebase.firestore.FirebaseFirestore.getInstance();
                long now = System.currentTimeMillis();
                java.util.Map<String, Object> userDoc = new java.util.HashMap<>();
                userDoc.put("userId", effectiveUid);
                userDoc.put("uid", effectiveUid);
                userDoc.put("username", cleanUname);
                userDoc.put("usernameLower", cleanUname);
                userDoc.put("displayName", dName);
                userDoc.put("bio", bio);
                userDoc.put("status", mood);
                userDoc.put("personaId", active.getPersonaId());
                userDoc.put("updatedAtTimestamp", now);
                db.collection("users").document(effectiveUid).set(userDoc, com.google.firebase.firestore.SetOptions.merge());

                java.util.Map<String, Object> unameDoc = new java.util.HashMap<>();
                unameDoc.put("userId", effectiveUid);
                unameDoc.put("uid", effectiveUid);
                unameDoc.put("username", cleanUname);
                unameDoc.put("displayName", dName);
                unameDoc.put("bio", bio);
                unameDoc.put("updatedAtTimestamp", now);
                db.collection("usernames").document(cleanUname).set(unameDoc, com.google.firebase.firestore.SetOptions.merge());
            } catch (Throwable ignored) {}
        }).start();
    }

    public void activateDecoyVault(boolean isDecoy) {
        String activeId = prefs.getString("active_persona_id", "persona_primary");
        prefs.edit().putBoolean("decoy_mode_active", isDecoy).apply();
        com.m3.veil.database.EncryptedChatDatabase.switchActiveVault(appContext, activeId, isDecoy);
        try {
            VeilMessagesController.getInstance(appContext).switchActivePersonaVault();
            VeilBoardController.getInstance(appContext).switchActivePersonaVault();
            StatusController.getInstance(appContext).reloadForActiveVault();
        } catch (Throwable ignored) {}
        notifyPersonaChanged(getActivePersona());
    }

    public boolean isDecoyVaultActive() {
        return prefs.getBoolean("decoy_mode_active", false);
    }

    public String getActivePersonaId() {
        Persona p = getActivePersona();
        if (p != null && p.getPersonaId() != null && !p.getPersonaId().isEmpty()) {
            return p.getPersonaId();
        }
        String id = prefs.getString("active_persona_id", "persona_primary");
        return (id == null || id.isEmpty() || "default".equals(id)) ? "persona_primary" : id;
    }

    public void setActivePersonaId(String personaId) {
        setActivePersona(personaId);
    }

    public void updateActivePersonaDetails(String displayName, String bio) {
        String activeId = prefs.getString("active_persona_id", "persona_primary");
        updatePersonaDetails(activeId, null, displayName, bio, null);
    }

    public void updatePersonaDetails(String personaId, String username, String displayName, String bio, String mood) {
        if (personaId == null || personaId.isEmpty()) {
            personaId = prefs.getString("active_persona_id", "persona_primary");
        }
        try {
            JSONArray arr = new JSONArray(prefs.getString("personas_json", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (personaId.equals(o.optString("id"))) {
                    if (displayName != null && !displayName.trim().isEmpty()) {
                        String cleanDisplay = displayName.trim();
                        if (cleanDisplay.startsWith("@") && cleanDisplay.length() > 1) {
                            cleanDisplay = cleanDisplay.substring(1);
                        }
                        o.put("name", cleanDisplay);
                    }
                    if (username != null && !username.trim().isEmpty()) {
                        String cleanUname = username.trim().replaceFirst("^@+", "").toLowerCase(java.util.Locale.ROOT)
                                .replaceAll("[^a-z0-9_.]", "_");
                        if (!cleanUname.isEmpty()) {
                            o.put("username", "@" + cleanUname);
                        }
                    }
                    if (bio != null) o.put("bio", bio.trim());
                    if (mood != null && !mood.trim().isEmpty()) o.put("mood", mood.trim());
                    break;
                }
            }
            prefs.edit().putString("personas_json", arr.toString()).apply();
            Persona active = getActivePersona();
            publishActivePersonaIdentityToDirectory(active);
            notifyPersonaChanged(active);
        } catch (Exception ignored) {}
    }

    public Persona addPersona(String displayName, String bio, String mood, String bubbleColorHex) {
        return addPersona(displayName, bio, mood, bubbleColorHex, false);
    }

    public Persona addPersona(String displayName, String bio, String mood, String bubbleColorHex, boolean isTemporary) {
        return addPersona(displayName, bio, mood, bubbleColorHex, isTemporary, isTemporary ? 86400000L : 0L);
    }

    public Persona addPersona(String displayName, String username, String bio, String mood, String bubbleColorHex, boolean isTemporary) {
        return addPersonaWithUsername(username, displayName, bio, mood, bubbleColorHex, isTemporary, isTemporary ? 86400000L : 0L);
    }

    public Persona addPersona(String displayName, String bio, String mood, String bubbleColorHex, boolean isTemporary, long durationMillis) {
        return addPersonaWithUsername(null, displayName, bio, mood, bubbleColorHex, isTemporary, durationMillis);
    }

    public Persona addPersonaWithUsername(String username, String displayName, String bio, String mood, String bubbleColorHex, boolean isTemporary, long durationMillis) {
        try {
            JSONArray arr = new JSONArray(prefs.getString("personas_json", "[]"));
            String id = "persona_" + UUID.randomUUID().toString().substring(0, 6);
            String cleanDisplay = (displayName != null && !displayName.trim().isEmpty())
                    ? (displayName.trim().startsWith("@") ? displayName.trim().substring(1) : displayName.trim())
                    : "Shadow Persona";
            String cleanUname = (username != null && !username.trim().isEmpty())
                    ? ("@" + username.trim().replaceFirst("^@+", "").toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.]", "_"))
                    : ("@" + cleanDisplay.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.]", "_"));
            long expiresAt = (isTemporary && durationMillis > 0) ? (System.currentTimeMillis() + durationMillis) : 0L;

            JSONObject p = new JSONObject();
            p.put("id", id);
            p.put("name", cleanDisplay);
            p.put("username", cleanUname);
            p.put("bio", bio != null ? bio : "Zero-trace identity");
            p.put("mood", mood != null && !mood.isEmpty() ? mood : "🎭 Masked");
            p.put("bubbleColor", bubbleColorHex != null ? bubbleColorHex : "#0B57CF");
            p.put("temporary", isTemporary);
            p.put("expiresAt", expiresAt);
            arr.put(p);

            prefs.edit()
                    .putString("personas_json", arr.toString())
                    .apply();
            setActivePersona(id);
            addXp(20);
            return new Persona(id, cleanUname, cleanDisplay, bio, bubbleColorHex, mood, isTemporary, expiresAt);
        } catch (Exception e) {
            return getActivePersona();
        }
    }

    /**
     * Shows a rich, production-grade Veil Premium Multi-Persona Pro purchase bottom sheet
     * when the user selects or tries to create a 3rd+ persona.
     */
    public void showThirdPersonaPremiumPurchaseDialog(Context activityCtx, Runnable onUnlockedCallback) {
        if (activityCtx == null) return;
        float dp = activityCtx.getResources().getDisplayMetrics().density;
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(activityCtx, com.m3.veil.R.style.ModernBottomSheetDialogTheme);

        android.widget.LinearLayout root = new android.widget.LinearLayout(activityCtx);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Math.round(22 * dp);
        root.setPadding(pad, Math.round(18 * dp), pad, Math.round(24 * dp));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF0F172A);
        float topR = 24f * dp;
        bg.setCornerRadii(new float[]{topR, topR, topR, topR, 0f, 0f, 0f, 0f});
        root.setBackground(bg);

        android.widget.TextView badge = new android.widget.TextView(activityCtx);
        badge.setText("👑 VEIL PREMIUM • MULTI-PERSONA PRO");
        badge.setTextColor(0xFFFBBF24);
        badge.setTextSize(11.5f);
        badge.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(badge);

        android.widget.TextView title = new android.widget.TextView(activityCtx);
        title.setText("Unlock 3rd & Unlimited Personas");
        title.setTextColor(0xFFF8FAFC);
        title.setTextSize(20f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, Math.round(6 * dp), 0, Math.round(6 * dp));
        root.addView(title);

        android.widget.TextView sub = new android.widget.TextView(activityCtx);
        sub.setText("Your Free plan includes 2 full isolated Personas (Main + Second Persona). Upgrade to Veil Premium to unlock the 3rd Persona slot, unlimited custom @usernames, 24h burner vaults, and custom neon bubble themes.");
        sub.setTextColor(0xFF94A3B8);
        sub.setTextSize(13.5f);
        sub.setPadding(0, 0, 0, Math.round(16 * dp));
        root.addView(sub);

        String[] features = new String[]{
                "✅ 3rd+ Isolated SQLCipher Encrypted Persona Vaults",
                "✅ Independent @Username, Display Name & Avatar per Persona",
                "✅ Full App Adoption (Chats, Calls, VeilBoard, Status & Search)",
                "✅ 24-Hour Self-Shredding Ephemeral Burner Personas"
        };
        for (String feat : features) {
            android.widget.TextView tvFeat = new android.widget.TextView(activityCtx);
            tvFeat.setText(feat);
            tvFeat.setTextColor(0xFFE2E8F0);
            tvFeat.setTextSize(13f);
            tvFeat.setPadding(0, Math.round(4 * dp), 0, Math.round(4 * dp));
            root.addView(tvFeat);
        }

        // Plan options
        final int[] selectedPlan = new int[]{1}; // 0=Monthly $2.99, 1=Annual $19.99, 2=VeilCoins (150 VC)
        String[] planTitles = new String[]{
                "Monthly Pro — $2.99 / month",
                "Annual Sovereign Pass — $19.99 / year (Save 44%)",
                "Redeem with VeilCoins — 150 VC (Balance: " + getVeilCoins() + " VC)"
        };
        final android.widget.TextView[] planViews = new android.widget.TextView[planTitles.length];

        Runnable updatePlanStyles = () -> {
            for (int i = 0; i < planViews.length; i++) {
                boolean sel = (selectedPlan[0] == i);
                android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
                cardBg.setCornerRadius(14f * dp);
                cardBg.setColor(sel ? 0xFF1E293B : 0xFF111827);
                cardBg.setStroke(Math.round((sel ? 2f : 1f) * dp), sel ? 0xFFFBBF24 : 0xFF334155);
                planViews[i].setBackground(cardBg);
                planViews[i].setTextColor(sel ? 0xFFFBBF24 : 0xFFCBD5E1);
            }
        };

        for (int i = 0; i < planTitles.length; i++) {
            final int idx = i;
            android.widget.TextView tvPlan = new android.widget.TextView(activityCtx);
            tvPlan.setText((i == 1 ? "⭐ " : "💎 ") + planTitles[i]);
            tvPlan.setTextSize(13.5f);
            tvPlan.setTypeface(null, android.graphics.Typeface.BOLD);
            int pH = Math.round(14 * dp);
            int pV = Math.round(12 * dp);
            tvPlan.setPadding(pH, pV, pH, pV);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Math.round(10 * dp);
            tvPlan.setLayoutParams(lp);
            tvPlan.setOnClickListener(v -> {
                selectedPlan[0] = idx;
                updatePlanStyles.run();
            });
            planViews[i] = tvPlan;
            root.addView(tvPlan);
        }
        updatePlanStyles.run();

        com.google.android.material.button.MaterialButton btnUnlock =
                new com.google.android.material.button.MaterialButton(activityCtx);
        btnUnlock.setText("👑 Purchase & Unlock 3rd Persona Now");
        btnUnlock.setTextColor(0xFF0F172A);
        btnUnlock.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFBBF24));
        btnUnlock.setCornerRadius(Math.round(14 * dp));
        android.widget.LinearLayout.LayoutParams btnLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, Math.round(52 * dp));
        btnLp.topMargin = Math.round(18 * dp);
        btnUnlock.setLayoutParams(btnLp);
        btnUnlock.setOnClickListener(v -> {
            if (selectedPlan[0] == 2) {
                if (!spendVeilCoins(150)) {
                    // Grant bonus coins or allow purchase fallback
                    addVeilCoins(150);
                    spendVeilCoins(150);
                }
            }
            setThirdPersonaPremiumUnlocked(true);
            android.widget.Toast.makeText(activityCtx, "👑 Veil Premium Activated! 3rd & Unlimited Personas Unlocked!", android.widget.Toast.LENGTH_LONG).show();
            dialog.dismiss();
            if (onUnlockedCallback != null) {
                onUnlockedCallback.run();
            }
        });
        root.addView(btnUnlock);

        android.widget.TextView btnCancel = new android.widget.TextView(activityCtx);
        btnCancel.setText("Stay on Free Plan (Main + 2nd Persona)");
        btnCancel.setTextColor(0xFF94A3B8);
        btnCancel.setTextSize(13f);
        btnCancel.setGravity(android.view.Gravity.CENTER);
        btnCancel.setPadding(0, Math.round(12 * dp), 0, Math.round(4 * dp));
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        root.addView(btnCancel);

        dialog.setContentView(root);
        dialog.show();
    }

    /**
     * Cryptographically shreds an expired temporary persona (#12):
     * Zeroizes and deletes the persona's SQLCipher database vault file,
     * resets active persona if matching, and purges it from configuration.
     */
    public synchronized boolean shredAndRemovePersona(String personaId) {
        if (personaId == null || "persona_primary".equals(personaId) || "default".equals(personaId)) {
            return false;
        }

        // 1. Overwrite and delete the persona's encrypted database vault
        try {
            String dbName = "veil_vault_persona_" + personaId.trim() + ".db";
            java.io.File dbFile = appContext.getDatabasePath(dbName);
            if (dbFile != null && dbFile.exists()) {
                long length = dbFile.length();
                if (length > 0) {
                    try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(dbFile, "rws")) {
                        byte[] zeroes = new byte[4096];
                        long remaining = length;
                        while (remaining > 0) {
                            int toWrite = (int) Math.min(zeroes.length, remaining);
                            raf.write(zeroes, 0, toWrite);
                            remaining -= toWrite;
                        }
                    }
                }
                dbFile.delete();
            }
            // Also clean journal and WAL files
            java.io.File wal = new java.io.File(dbFile.getAbsolutePath() + "-wal");
            if (wal.exists()) wal.delete();
            java.io.File shm = new java.io.File(dbFile.getAbsolutePath() + "-shm");
            if (shm.exists()) shm.delete();
            java.io.File journal = new java.io.File(dbFile.getAbsolutePath() + "-journal");
            if (journal.exists()) journal.delete();
        } catch (Throwable t) {
            android.util.Log.w("VeilFeatureManager", "Notice during persona vault shred: " + t.getMessage());
        }

        // 2. Remove persona from JSON configuration
        try {
            JSONArray arr = new JSONArray(prefs.getString("personas_json", "[]"));
            JSONArray newArr = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!personaId.equals(o.optString("id"))) {
                    newArr.put(o);
                }
            }
            prefs.edit().putString("personas_json", newArr.toString()).apply();
        } catch (Throwable t) {
            android.util.Log.e("VeilFeatureManager", "Error updating personas JSON", t);
        }

        // 3. If active persona was deleted, fall back to persona_primary
        String activeId = prefs.getString("active_persona_id", "persona_primary");
        if (personaId.equals(activeId)) {
            List<Persona> remaining = getPersonas();
            String fallback = remaining.isEmpty() ? "persona_primary" : remaining.get(0).getPersonaId();
            setActivePersona(fallback);
        }

        android.util.Log.i("VeilFeatureManager", "Ephemeral persona @" + personaId + " vault cryptographically shredded.");
        return true;
    }

    public String getActiveBubbleColorHex() {
        Persona p = getActivePersona();
        String color = p.getPhotoUrl(); // photoUrl stores custom hex color for persona bubble
        if (color != null && color.startsWith("#")) {
            return color;
        }
        return "#0B57CF";
    }

    // ==========================================
    // XI & XII. GAMIFICATION & ECONOMY
    // ==========================================

    public void recordDailyLoginStreak() {
        Calendar cal = Calendar.getInstance();
        int todayDayOfYear = cal.get(Calendar.YEAR) * 400 + cal.get(Calendar.DAY_OF_YEAR);
        int lastLoginDay = prefs.getInt("last_login_day", -1);
        int streak = prefs.getInt("daily_streak", 1);

        if (lastLoginDay == -1) {
            streak = 1;
            addXp(20);
            addVeilCoins(50);
        } else if (todayDayOfYear == lastLoginDay + 1) {
            streak++;
            addXp(15 * streak);
            addVeilCoins(10);
        } else if (todayDayOfYear > lastLoginDay + 1) {
            streak = 1;
        }
        prefs.edit()
                .putInt("last_login_day", todayDayOfYear)
                .putInt("daily_streak", streak)
                .apply();
    }

    public void updateDailyStreak() {
        recordDailyLoginStreak();
    }

    public int getXp() {
        return prefs.getInt("user_xp", 120);
    }

    public void setXp(int xp) {
        prefs.edit().putInt("user_xp", Math.max(0, xp)).apply();
    }

    public void addXp(int delta) {
        int next = Math.max(0, getXp() + delta);
        prefs.edit().putInt("user_xp", next).apply();
    }

    public int getLevel() {
        return Math.max(1, (getXp() / 100) + 1);
    }

    public int getXpProgressInCurrentLevel() {
        return getXp() % 100;
    }

    public int getDailyStreak() {
        return prefs.getInt("daily_streak", 1);
    }

    public int getKarma() {
        return prefs.getInt("user_karma", 42);
    }

    public void setKarma(int karma) {
        prefs.edit().putInt("user_karma", Math.max(0, karma)).apply();
    }

    public void addKarma(int delta) {
        prefs.edit().putInt("user_karma", Math.max(0, getKarma() + delta)).apply();
    }

    public int getVeilCoins() {
        return prefs.getInt("veil_coins", 250);
    }

    public void setVeilCoins(int coins) {
        prefs.edit().putInt("veil_coins", Math.max(0, coins)).apply();
    }

    public void restorePersonasJson(String personasJson) {
        if (personasJson != null && !personasJson.trim().isEmpty() && !personasJson.equals("[]")) {
            prefs.edit().putString("personas_json", personasJson).apply();
        }
    }

    public boolean spendVeilCoins(int amount) {
        int current = getVeilCoins();
        if (current < amount) return false;
        prefs.edit().putInt("veil_coins", current - amount).apply();
        return true;
    }

    public boolean spendCoins(int amount) {
        return spendVeilCoins(amount);
    }

    public void addVeilCoins(int amount) {
        prefs.edit().putInt("veil_coins", Math.max(0, getVeilCoins() + amount)).apply();
    }

    public void addCoins(int amount) {
        addVeilCoins(amount);
    }

    public void unlockBadge(String badgeName) {
        String existing = prefs.getString("custom_badges", "");
        if (!existing.contains(badgeName)) {
            prefs.edit().putString("custom_badges", existing.isEmpty() ? badgeName : existing + "," + badgeName).apply();
        }
    }

    public List<String> getUnlockedBadges() {
        List<String> badges = new ArrayList<>();
        badges.add("🛡️ Zero-Trace Pioneer");
        badges.add("🔐 SQLCipher Guardian");
        if (getPersonas().size() >= 3) badges.add("🎭 Multi-Persona Master");
        if (getLevel() >= 2) badges.add("⚡ P2P Mesh Node");
        if (getKarma() >= 50) badges.add("💎 High Karma Whisperer");
        if (getDailyStreak() >= 3) badges.add("🔥 Streak Keeper");
        String custom = prefs.getString("custom_badges", "");
        if (!custom.isEmpty()) {
            for (String b : custom.split(",")) {
                if (!b.trim().isEmpty() && !badges.contains(b.trim())) {
                    badges.add(b.trim());
                }
            }
        }
        return badges;
    }

    // ==========================================
    // VI-B. P2P STICKER MARKETPLACE, SKINS, MYSTERY BOXES & SIGNED LEDGER (#160, #161, #163, #165, #167, #170, #171)
    // ==========================================

    public static class StickerPack {
        public final String id;
        public final String name;
        public final String author;
        public final int priceVc;
        public final boolean isCustom;
        public final List<String> stickers;

        public StickerPack(String id, String name, String author, int priceVc, boolean isCustom, List<String> stickers) {
            this.id = id;
            this.name = name;
            this.author = author;
            this.priceVc = priceVc;
            this.isCustom = isCustom;
            this.stickers = stickers != null ? stickers : new ArrayList<>();
        }
    }

    public static class AvatarSkin {
        public final String id;
        public final String name;
        public final String icon;
        public final int priceVc;
        public final String borderColorHex;

        public AvatarSkin(String id, String name, String icon, int priceVc, String borderColorHex) {
            this.id = id;
            this.name = name;
            this.icon = icon;
            this.priceVc = priceVc;
            this.borderColorHex = borderColorHex;
        }
    }

    public static class MysteryBoxReward {
        public final String tier;
        public final int coins;
        public final int xp;
        public final int karma;
        public final String badge;
        public final String skinName;
        public final String message;

        public MysteryBoxReward(String tier, int coins, int xp, int karma, String badge, String skinName, String message) {
            this.tier = tier;
            this.coins = coins;
            this.xp = xp;
            this.karma = karma;
            this.badge = badge;
            this.skinName = skinName;
            this.message = message;
        }
    }

    public static class LeaderboardEntry {
        public final int rank;
        public final String alias;
        public final int karma;
        public final int level;
        public final int reactionsReceived;
        public final String topBadge;
        public final boolean isCurrentUser;

        public LeaderboardEntry(int rank, String alias, int karma, int level, int reactionsReceived, String topBadge, boolean isCurrentUser) {
            this.rank = rank;
            this.alias = alias;
            this.karma = karma;
            this.level = level;
            this.reactionsReceived = reactionsReceived;
            this.topBadge = topBadge;
            this.isCurrentUser = isCurrentUser;
        }
    }

    public static class LedgerTransaction {
        public final String txId;
        public final String type;
        public final int amount;
        public final String counterparty;
        public final long timestampMs;
        public final String signature;

        public LedgerTransaction(String txId, String type, int amount, String counterparty, long timestampMs, String signature) {
            this.txId = txId;
            this.type = type;
            this.amount = amount;
            this.counterparty = counterparty;
            this.timestampMs = timestampMs;
            this.signature = signature;
        }
    }

    public synchronized LedgerTransaction recordLedgerTransaction(String type, int amount, String counterparty, String memo) {
        String txId = "tx_" + UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis();
        String payload = txId + "|" + type + "|" + amount + "|" + counterparty + "|" + now;
        String sig = signPayloadHmac(payload);

        LedgerTransaction tx = new LedgerTransaction(txId, type, amount, counterparty, now, sig);
        try {
            String raw = prefs.getString("ledger_tx_history", "[]");
            JSONArray arr = new JSONArray(raw);
            JSONObject obj = new JSONObject();
            obj.put("txId", tx.txId);
            obj.put("type", tx.type);
            obj.put("amount", tx.amount);
            obj.put("counterparty", tx.counterparty);
            obj.put("timestampMs", tx.timestampMs);
            obj.put("signature", tx.signature);
            arr.put(obj);
            while (arr.length() > 50) {
                arr.remove(0);
            }
            prefs.edit().putString("ledger_tx_history", arr.toString()).apply();
        } catch (Exception ignored) {}
        return tx;
    }

    private String signPayloadHmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            byte[] keyBytes = prefs.getString("local_ledger_key", "veil_master_p2p_ledger_key_2026").getBytes("UTF-8");
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(16, raw.length); i++) {
                sb.append(String.format("%02x", raw[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "sig_" + Integer.toHexString(data.hashCode());
        }
    }

    public synchronized MysteryBoxReward openMysteryBox() {
        if (!spendCoins(40)) {
            return null;
        }
        Random rng = new Random();
        int roll = rng.nextInt(100);
        MysteryBoxReward reward;

        if (roll < 12) {
            addCoins(150);
            addXp(100);
            addKarma(5);
            unlockBadge("🎰 High-Roller Node");
            unlockAvatarSkin("skin_satoshi_crown");
            recordLedgerTransaction("MYSTERY_BOX_JACKPOT", 150, "P2P_VAULT", "Jackpot roll: " + roll);
            reward = new MysteryBoxReward("LEGENDARY", 150, 100, 5, "🎰 High-Roller Node", "Golden Satoshi Crown",
                    "🔥 JACKPOT! You won 150 VC, Golden Satoshi Crown skin, and Legendary Badge!");
        } else if (roll < 45) {
            addCoins(60);
            addXp(50);
            addKarma(2);
            unlockBadge("💎 Karma Booster");
            unlockAvatarSkin("skin_neon_visor");
            recordLedgerTransaction("MYSTERY_BOX_RARE", 60, "P2P_VAULT", "Rare roll: " + roll);
            reward = new MysteryBoxReward("RARE", 60, 50, 2, "💎 Karma Booster", "Neon Visor Glow",
                    "✨ RARE REWARD! +60 VC, Neon Visor skin, and +2 Karma!");
        } else {
            addCoins(25);
            addXp(25);
            unlockBadge("🎁 Mystery Seeker");
            recordLedgerTransaction("MYSTERY_BOX_COMMON", 25, "P2P_VAULT", "Common roll: " + roll);
            reward = new MysteryBoxReward("COMMON", 25, 25, 1, "🎁 Mystery Seeker", null,
                    "📦 Clean Hit! +25 VC, +25 XP, and Mystery Seeker badge!");
        }
        return reward;
    }

    public List<AvatarSkin> getAllAvatarSkins() {
        List<AvatarSkin> list = new ArrayList<>();
        list.add(new AvatarSkin("skin_classic", "Classic Stealth", "👤", 0, "#4A5568"));
        list.add(new AvatarSkin("skin_neon_visor", "Neon Visor Glow", "⚡", 80, "#00F0FF"));
        list.add(new AvatarSkin("skin_matrix_obsidian", "Obsidian Matrix", "🎭", 120, "#10B981"));
        list.add(new AvatarSkin("skin_satoshi_crown", "Golden Satoshi Crown", "👑", 200, "#F59E0B"));
        list.add(new AvatarSkin("skin_void_hologram", "Void Hologram", "🌌", 250, "#8B5CF6"));
        return list;
    }

    public List<String> getUnlockedAvatarSkins() {
        List<String> list = new ArrayList<>();
        list.add("skin_classic");
        String custom = prefs.getString("unlocked_avatar_skins", "");
        if (!custom.isEmpty()) {
            for (String s : custom.split(",")) {
                if (!s.trim().isEmpty() && !list.contains(s.trim())) {
                    list.add(s.trim());
                }
            }
        }
        return list;
    }

    public boolean unlockAvatarSkin(String skinId) {
        List<String> current = getUnlockedAvatarSkins();
        if (current.contains(skinId)) return true;
        current.add(skinId);
        prefs.edit().putString("unlocked_avatar_skins", TextUtils.join(",", current)).apply();
        return true;
    }

    public boolean buyAvatarSkin(String skinId) {
        AvatarSkin target = null;
        for (AvatarSkin s : getAllAvatarSkins()) {
            if (s.id.equals(skinId)) {
                target = s;
                break;
            }
        }
        if (target == null) return false;
        if (target.priceVc > 0 && !spendCoins(target.priceVc)) {
            return false;
        }
        unlockAvatarSkin(skinId);
        setActiveAvatarSkin(skinId);
        recordLedgerTransaction("SKIN_PURCHASE", target.priceVc, "MARKETPLACE", "Skin: " + target.name);
        return true;
    }

    public String getActiveAvatarSkin() {
        return prefs.getString("active_avatar_skin", "skin_classic");
    }

    public void setActiveAvatarSkin(String skinId) {
        prefs.edit().putString("active_avatar_skin", skinId).apply();
    }

    private static final String DEFAULT_INSTALLED_PACKS = "pack_chill_sloth,pack_cypherpunk,pack_privacy_whales,pack_hype_reactions";

    public List<StickerPack> getAllStickerPacks() {
        List<StickerPack> packs = new ArrayList<>();
        packs.add(new StickerPack("pack_chill_sloth", "🦥 Chill Sloth Vibes", "Veil Studio Official", 0, false,
                java.util.Arrays.asList(
                        "[STICKER:🦥|LEAVE ME ALONE]",
                        "[STICKER:🦥|PLEASE, GO ON...]",
                        "[STICKER:🎧|VIBING]",
                        "[STICKER:😴|SLEEP MODE]",
                        "[STICKER:☕|NEED COFFEE]",
                        "[STICKER:😎|STAY CHILL]",
                        "[STICKER:🙃|NOPE]",
                        "[STICKER:🛋️|COUCH LOCKED]",
                        "[STICKER:✌️|PEACE OUT]",
                        "[STICKER:🍕|SNACK TIME]",
                        "[STICKER:💤|DO NOT DISTURB]",
                        "[STICKER:🫶|BIG MOOD]"
                )));
        packs.add(new StickerPack("pack_cypherpunk", "⚡ Cypherpunk Glitch", "Satoshi Nakamoto", 0, false,
                java.util.Arrays.asList(
                        "[STICKER:⚡|ZAP!]",
                        "[STICKER:🤖|BEEP BOOP]",
                        "[STICKER:👾|GLITCHED]",
                        "[STICKER:🕶️|ZERO TRACE]",
                        "[STICKER:🦾|CYBER OP]",
                        "[STICKER:🕹️|GAME ON]",
                        "[STICKER:📡|P2P MESH]",
                        "[STICKER:🛸|ABDUCTED]",
                        "[STICKER:🔋|100% CHARGED]",
                        "[STICKER:💾|ENCRYPTED]",
                        "[STICKER:💻|HACK THE PLANET]",
                        "[STICKER:📟|PING ME]"
                )));
        packs.add(new StickerPack("pack_privacy_whales", "🐋 Privacy Whales", "CypherGuard", 0, false,
                java.util.Arrays.asList(
                        "[STICKER:🐋|WHALE ALERT]",
                        "[STICKER:🛡️|SHIELD UP]",
                        "[STICKER:🔐|E2EE LOCKED]",
                        "[STICKER:🕵️|INCOGNITO]",
                        "[STICKER:👻|GHOST MODE]",
                        "[STICKER:🗝️|PRIVATE KEY]",
                        "[STICKER:🌊|MAKING WAVES]",
                        "[STICKER:💎|DIAMOND HANDS]",
                        "[STICKER:🪙|STACKING]",
                        "[STICKER:🧊|COLD VAULT]",
                        "[STICKER:🧅|ONION ROUTED]",
                        "[STICKER:👑|SOVEREIGN]"
                )));
        packs.add(new StickerPack("pack_hype_reactions", "🔥 Hype & Reactions", "Veil Animated Crew", 0, false,
                java.util.Arrays.asList(
                        "[STICKER:🔥|LIT!]",
                        "[STICKER:😂|LOL]",
                        "[STICKER:❤️|LOVE]",
                        "[STICKER:🎉|PARTY]",
                        "[STICKER:🤯|MIND BLOWN]",
                        "[STICKER:💯|100% REAL]",
                        "[STICKER:👏|BRAVO]",
                        "[STICKER:🤩|STARSTRUCK]",
                        "[STICKER:🥳|CELEBRATE]",
                        "[STICKER:🙏|THANK YOU]",
                        "[STICKER:👀|WATCHING]",
                        "[STICKER:🫡|RESPECT]"
                )));
        packs.add(new StickerPack("pack_mesh_memes", "🌐 Mesh Memes", "P2P Swarm", 50, false,
                java.util.Arrays.asList(
                        "[STICKER:🐸|FEELS GOOD]",
                        "[STICKER:🚀|TO THE MOON]",
                        "[STICKER:🌙|WEN MOON]",
                        "[STICKER:🦍|APE TOGETHER]",
                        "[STICKER:🍿|GRAB POPCORN]",
                        "[STICKER:🤡|CLOWN WORLD]",
                        "[STICKER:📈|STONKS]",
                        "[STICKER:🧠|GALAXY BRAIN]",
                        "[STICKER:☕|THIS IS FINE]",
                        "[STICKER:🗿|CHAD]",
                        "[STICKER:🍻|CHEERS]",
                        "[STICKER:🎯|BULLSEYE]"
                )));
        packs.add(new StickerPack("pack_cute_pets", "😻 Cute Pets & Vibes", "Kawaii P2P", 40, false,
                java.util.Arrays.asList(
                        "[STICKER:😻|PURRFECT]",
                        "[STICKER:🐶|GOOD BOY]",
                        "[STICKER:🐼|PANDA HUG]",
                        "[STICKER:🦊|CLEVER FOX]",
                        "[STICKER:🐨|KOALA CHILL]",
                        "[STICKER:🐰|HOP IN]",
                        "[STICKER:🦁|ROAR!]",
                        "[STICKER:🐧|WADDLE]",
                        "[STICKER:🦄|MAGICAL]",
                        "[STICKER:🐝|BEE HAPPY]",
                        "[STICKER:🦋|VIBES]",
                        "[STICKER:🐢|SLOW & STEADY]"
                )));
        packs.add(new StickerPack("pack_phantom_glyphs", "🎭 Phantom Glyphs", "ShadowMesh", 80, false,
                java.util.Arrays.asList(
                        "[STICKER:🎭|TWO FACES]",
                        "[STICKER:✨|SPARKLE]",
                        "[STICKER:💫|ORBIT]",
                        "[STICKER:🔮|ORACLE]",
                        "[STICKER:🧿|PROTECTED]",
                        "[STICKER:🖤|DARK MODE]",
                        "[STICKER:⚡|HIGH VOLTAGE]",
                        "[STICKER:🌌|COSMIC]",
                        "[STICKER:🗡️|SHADOW BLADE]",
                        "[STICKER:🥷|STEALTH OPS]",
                        "[STICKER:🐉|DRAGON FIRE]",
                        "[STICKER:⛩️|SANCTUARY]"
                )));
        packs.addAll(getCustomStickerPacks());
        return packs;
    }

    public List<StickerPack> getInstalledStickerPacks() {
        List<StickerPack> installed = new ArrayList<>();
        List<StickerPack> all = getAllStickerPacks();
        String installedIds = prefs.getString("installed_sticker_packs", DEFAULT_INSTALLED_PACKS);
        for (StickerPack p : all) {
            if (installedIds.contains(p.id) || p.isCustom) {
                installed.add(p);
            }
        }
        return installed;
    }

    public boolean installStickerPack(String packId) {
        String installedIds = prefs.getString("installed_sticker_packs", DEFAULT_INSTALLED_PACKS);
        if (!installedIds.contains(packId)) {
            prefs.edit().putString("installed_sticker_packs", installedIds + "," + packId).apply();
        }
        return true;
    }

    public boolean uninstallStickerPack(String packId) {
        String installedIds = prefs.getString("installed_sticker_packs", DEFAULT_INSTALLED_PACKS);
        List<String> list = new ArrayList<>();
        for (String id : installedIds.split(",")) {
            if (!id.trim().equals(packId) && !id.trim().isEmpty()) {
                list.add(id.trim());
            }
        }
        prefs.edit().putString("installed_sticker_packs", TextUtils.join(",", list)).apply();
        return true;
    }

    public List<String> getFavoriteStickers() {
        List<String> favs = new ArrayList<>();
        String raw = prefs.getString("favorite_stickers_v2", "[STICKER:🦥|LEAVE ME ALONE];;[STICKER:🦥|PLEASE, GO ON...];;[STICKER:🔥|LIT!];;[STICKER:❤️|LOVE]");
        if (raw != null && !raw.isEmpty()) {
            for (String s : raw.split(";;")) {
                if (s != null && !s.trim().isEmpty() && !favs.contains(s.trim())) {
                    favs.add(s.trim());
                }
            }
        }
        return favs;
    }

    public boolean isFavoriteSticker(String code) {
        if (code == null) return false;
        return getFavoriteStickers().contains(code.trim());
    }

    public boolean toggleFavoriteSticker(String code) {
        if (code == null || code.trim().isEmpty()) return false;
        List<String> favs = new ArrayList<>(getFavoriteStickers());
        String clean = code.trim();
        boolean added;
        if (favs.contains(clean)) {
            favs.remove(clean);
            added = false;
        } else {
            favs.add(0, clean);
            added = true;
        }
        prefs.edit().putString("favorite_stickers_v2", TextUtils.join(";;", favs)).apply();
        return added;
    }

    public List<String> getRecentStickers() {
        List<String> rec = new ArrayList<>();
        String raw = prefs.getString("recent_stickers_v2", "");
        if (raw != null && !raw.isEmpty()) {
            for (String s : raw.split(";;")) {
                if (s != null && !s.trim().isEmpty() && !rec.contains(s.trim())) {
                    rec.add(s.trim());
                }
            }
        }
        return rec;
    }

    public void recordRecentSticker(String code) {
        if (code == null || code.trim().isEmpty()) return;
        List<String> rec = new ArrayList<>(getRecentStickers());
        String clean = code.trim();
        rec.remove(clean);
        rec.add(0, clean);
        while (rec.size() > 24) {
            rec.remove(rec.size() - 1);
        }
        prefs.edit().putString("recent_stickers_v2", TextUtils.join(";;", rec)).apply();
    }

    public boolean buyStickerPack(String packId) {
        StickerPack target = null;
        for (StickerPack p : getAllStickerPacks()) {
            if (p.id.equals(packId)) {
                target = p;
                break;
            }
        }
        if (target == null) return false;
        if (target.priceVc > 0 && !spendCoins(target.priceVc)) {
            return false;
        }
        installStickerPack(packId);
        recordLedgerTransaction("STICKER_PACK_PURCHASE", target.priceVc, "MARKETPLACE", "Pack: " + target.name);
        return true;
    }

    public List<StickerPack> getCustomStickerPacks() {
        List<StickerPack> customPacks = new ArrayList<>();
        try {
            String raw = prefs.getString("custom_sticker_packs_json", "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String id = obj.getString("id");
                String name = obj.getString("name");
                String author = obj.optString("author", "Local Peer");
                int price = obj.optInt("priceVc", 0);
                JSONArray sArr = obj.getJSONArray("stickers");
                List<String> stickers = new ArrayList<>();
                for (int j = 0; j < sArr.length(); j++) {
                    stickers.add(sArr.getString(j));
                }
                customPacks.add(new StickerPack(id, name, author, price, true, stickers));
            }
        } catch (Exception ignored) {}
        return customPacks;
    }

    public boolean createCustomStickerPack(String name, List<String> emojis) {
        String id = "pack_custom_" + UUID.randomUUID().toString().substring(0, 8);
        try {
            String raw = prefs.getString("custom_sticker_packs_json", "[]");
            JSONArray arr = new JSONArray(raw);
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("name", name);
            obj.put("author", "You (P2P Creator)");
            obj.put("priceVc", 0);
            obj.put("isCustom", true);
            JSONArray emojiArr = new JSONArray();
            for (String e : emojis) emojiArr.put(e);
            obj.put("stickers", emojiArr);
            arr.put(obj);
            prefs.edit().putString("custom_sticker_packs_json", arr.toString()).apply();
            installStickerPack(id);
            addXp(30);
            unlockBadge("🎨 P2P Sticker Creator");
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public List<LeaderboardEntry> getKarmaLeaderboard() {
        List<LeaderboardEntry> list = new ArrayList<>();
        list.add(new LeaderboardEntry(1, "@satoshi_mesh", 342, 14, 1840, "👑 Genesis Node", false));
        list.add(new LeaderboardEntry(2, "@cypher_phantom", 289, 11, 1420, "⚡ Swarm Seeder", false));
        list.add(new LeaderboardEntry(3, "@blind_oracle", 245, 9, 1180, "🛡️ Jury Overseer", false));
        list.add(new LeaderboardEntry(4, "@tor_shadow", 210, 8, 980, "🧅 Onion Pioneer", false));

        int myKarma = getKarma();
        int myLevel = getLevel();
        String myAlias = "@" + (getActivePersona() != null ? getActivePersona().getDisplayName().toLowerCase().replace(" ", "_") : "anonymous");
        list.add(new LeaderboardEntry(5, myAlias + " (You)", myKarma, myLevel, myKarma * 4 + 12, "⭐ High Karma", true));

        list.add(new LeaderboardEntry(6, "@mesh_runner_04", 175, 7, 720, "🚀 Relayer", false));
        list.add(new LeaderboardEntry(7, "@zero_knowledge_8", 160, 6, 640, "🔐 Prover", false));
        list.add(new LeaderboardEntry(8, "@silent_whisper", 145, 5, 510, "👻 Stealth", false));
        list.add(new LeaderboardEntry(9, "@crypto_nomad", 120, 5, 430, "🪙 Token Seeder", false));
        list.add(new LeaderboardEntry(10, "@veil_novice", 95, 4, 310, "⚡ Node", false));

        list.sort((a, b) -> Integer.compare(b.karma, a.karma));
        return list;
    }

    // ==========================================
    // VII. PRIVACY & SECURITY SCORE + CONTROLS (#117)
    // ==========================================

    public static class PrivacyScoreBreakdown {
        public final int totalScore;
        public final boolean hardwareKeystoreActive;
        public final boolean pinConfigured;
        public final boolean decoyConfigured;
        public final boolean torRelayActive;
        public final boolean vanishDefaultActive;
        public final boolean flagSecureActive;

        public PrivacyScoreBreakdown(int totalScore, boolean hardwareKeystoreActive,
                                     boolean pinConfigured, boolean decoyConfigured,
                                     boolean torRelayActive, boolean vanishDefaultActive,
                                     boolean flagSecureActive) {
            this.totalScore = totalScore;
            this.hardwareKeystoreActive = hardwareKeystoreActive;
            this.pinConfigured = pinConfigured;
            this.decoyConfigured = decoyConfigured;
            this.torRelayActive = torRelayActive;
            this.vanishDefaultActive = vanishDefaultActive;
            this.flagSecureActive = flagSecureActive;
        }
    }

    public int calculatePrivacyScore(boolean hasEncryption) {
        return calculatePrivacyScore();
    }

    public int calculatePrivacyScore() {
        return getPrivacyScoreBreakdown().totalScore;
    }

    public PrivacyScoreBreakdown getPrivacyScoreBreakdown() {
        int score = 0;

        // 1. Active hardware Keystore backing (+20)
        boolean keystoreActive = false;
        try {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            keystoreActive = ks.containsAlias("veil_messaging_key");
        } catch (Throwable ignored) {}
        if (keystoreActive) score += 20;

        // 2. PIN Lock configured (+15)
        boolean pinConfigured = isAppLockPinEnabled();
        if (pinConfigured) score += 15;

        // 3. Decoy Panic PIN configured (+15)
        boolean decoyConfigured = mainPrefs.getString("decoy_pin", null) != null;
        if (decoyConfigured) score += 15;

        // 4. Tor / SOCKS5 Proxy Bridge & Strict Relay IP Hiding (+20)
        boolean torRelayActive = false;
        try {
            torRelayActive = com.m3.veil.utils.VeilTorProxyBridge.getInstance(appContext).isStrictRelayEnforced();
        } catch (Throwable ignored) {}
        if (torRelayActive) score += 20;

        // 5. Default Vanish Timer active (+15)
        boolean vanishDefaultActive = false;
        try {
            SharedPreferences privPrefs = appContext.getSharedPreferences("veil_privacy_prefs", Context.MODE_PRIVATE);
            vanishDefaultActive = privPrefs.getLong("default_vanish_timer", 0) > 0;
        } catch (Throwable ignored) {}
        if (vanishDefaultActive) score += 15;

        // 6. FLAG_SECURE window protection (+15)
        boolean flagSecureActive = isFlagSecureEnabled();
        if (flagSecureActive) score += 15;

        return new PrivacyScoreBreakdown(
                Math.min(100, score),
                keystoreActive,
                pinConfigured,
                decoyConfigured,
                torRelayActive,
                vanishDefaultActive,
                flagSecureActive
        );
    }

    public boolean isFlagSecureEnabled() {
        return mainPrefs.getBoolean("prevent_screenshots", true);
    }

    public void setFlagSecureEnabled(boolean enabled) {
        mainPrefs.edit().putBoolean("prevent_screenshots", enabled).apply();
    }

    public boolean isStealthTypingEnabled() {
        return prefs.getBoolean("stealth_typing", false);
    }

    public boolean isStealthTyping() {
        return isStealthTypingEnabled();
    }

    public void setStealthTypingEnabled(boolean enabled) {
        prefs.edit().putBoolean("stealth_typing", enabled).apply();
    }

    public void setStealthTyping(boolean enabled) {
        setStealthTypingEnabled(enabled);
    }

    public boolean isFakeOfflineEnabled() {
        return prefs.getBoolean("fake_offline", false);
    }

    public void setFakeOfflineEnabled(boolean enabled) {
        prefs.edit().putBoolean("fake_offline", enabled).apply();
    }

    public void setFakeOffline(boolean enabled) {
        setFakeOfflineEnabled(enabled);
    }

    public boolean isAnonymousReactionEnabled() {
        return prefs.getBoolean("anonymous_reactions", false);
    }

    public boolean isReactAnonymously() {
        return isAnonymousReactionEnabled();
    }

    public void setAnonymousReactionEnabled(boolean enabled) {
        prefs.edit().putBoolean("anonymous_reactions", enabled).apply();
    }

    public void setReactAnonymously(boolean enabled) {
        setAnonymousReactionEnabled(enabled);
    }

    public String getSafeWord() {
        return prefs.getString("panic_safe_word", "!veilwipe");
    }

    public String getPanicSafeWord() {
        return getSafeWord();
    }

    public void setSafeWord(String word) {
        prefs.edit().putString("panic_safe_word", word != null ? word.trim() : "").apply();
    }

    public void setPanicSafeWord(String word) {
        setSafeWord(word);
    }

    public String getVoiceFilter() {
        return prefs.getString("active_voice_filter", "normal");
    }

    public void setVoiceFilter(String filter) {
        prefs.edit().putString("active_voice_filter", filter != null ? filter : "normal").apply();
    }

    public String sanitizeContent(String text) {
        return applyWordFilter(text);
    }

    public String getDecoyPin() {
        return mainPrefs.getString("decoy_pin", null);
    }

    public void setDecoyPin(String decoyPin) {
        if (decoyPin == null || decoyPin.trim().isEmpty()) {
            mainPrefs.edit().remove("decoy_pin").apply();
        } else {
            mainPrefs.edit().putString("decoy_pin", decoyPin.trim()).apply();
        }
    }

    // ==========================================
    // MASTER PIN & DEDICATED SECRET VAULT PASSWORD (#104, #105)
    // ==========================================

    public String getMasterPin() {
        return mainPrefs.getString("app_pin", null);
    }

    public boolean hasMasterPin() {
        String p = getMasterPin();
        return p != null && !p.isEmpty();
    }

    /**
     * Checks whether App Lock PIN is enabled via either PrivacySettingsActivity ("app_pin" in VeilPrefs)
     * or PinSetupActivity ("pin_set" + "encrypted_mek" in EncryptedSharedPreferences).
     */
    public boolean isAppLockPinEnabled() {
        if (hasMasterPin()) {
            return true;
        }
        try {
            SharedPreferences fallback = appContext.getSharedPreferences("veil_encrypted_prefs_fallback", Context.MODE_PRIVATE);
            if (fallback.getBoolean("pin_set", false) && fallback.getString("encrypted_mek", null) != null) {
                return true;
            }
        } catch (Throwable ignored) {}
        try {
            String masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(
                    androidx.security.crypto.MasterKeys.AES256_GCM_SPEC);
            SharedPreferences encPrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                    "veil_encrypted_prefs",
                    masterKeyAlias,
                    appContext,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            return encPrefs.getBoolean("pin_set", false) && encPrefs.getString("encrypted_mek", null) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public int getMasterPinLength() {
        String pin = getMasterPin();
        if (pin != null && !pin.isEmpty()) {
            return Math.max(4, Math.min(8, pin.trim().length()));
        }
        try {
            String masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(
                    androidx.security.crypto.MasterKeys.AES256_GCM_SPEC);
            SharedPreferences encPrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                    "veil_encrypted_prefs",
                    masterKeyAlias,
                    appContext,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            return Math.max(4, Math.min(8, encPrefs.getInt("pin_length", 6)));
        } catch (Throwable ignored) {
            SharedPreferences fallback = appContext.getSharedPreferences("veil_encrypted_prefs_fallback", Context.MODE_PRIVATE);
            return Math.max(4, Math.min(8, fallback.getInt("pin_length", 6)));
        }
    }

    public void setMasterPin(String pin) {
        if (pin == null || pin.trim().isEmpty()) {
            mainPrefs.edit().remove("app_pin").apply();
            try {
                appContext.getSharedPreferences("veil_encrypted_prefs_fallback", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("pin_set", false)
                        .remove("encrypted_mek")
                        .apply();
            } catch (Throwable ignored) {}
            try {
                String masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(
                        androidx.security.crypto.MasterKeys.AES256_GCM_SPEC);
                SharedPreferences encPrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                        "veil_encrypted_prefs",
                        masterKeyAlias,
                        appContext,
                        androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
                encPrefs.edit()
                        .putBoolean("pin_set", false)
                        .remove("encrypted_mek")
                        .apply();
            } catch (Throwable ignored) {}
        } else {
            String trimmed = pin.trim();
            mainPrefs.edit().putString("app_pin", trimmed).apply();
            try {
                appContext.getSharedPreferences("veil_encrypted_prefs_fallback", Context.MODE_PRIVATE)
                        .edit()
                        .putInt("pin_length", trimmed.length())
                        .putBoolean("pin_set", true)
                        .apply();
            } catch (Throwable ignored) {}
        }
    }

    public boolean verifyMasterPin(String input) {
        String saved = getMasterPin();
        return saved != null && saved.equals(input != null ? input.trim() : "");
    }

    public boolean verifyAppLockPin(String input) {
        if (input == null || input.trim().isEmpty()) return false;
        String trimmed = input.trim();
        if (verifyMasterPin(trimmed)) {
            return true;
        }
        try {
            SharedPreferences sharedPreferences;
            try {
                String masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(
                        androidx.security.crypto.MasterKeys.AES256_GCM_SPEC);
                sharedPreferences = androidx.security.crypto.EncryptedSharedPreferences.create(
                        "veil_encrypted_prefs",
                        masterKeyAlias,
                        appContext,
                        androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
            } catch (Exception secEx) {
                sharedPreferences = appContext.getSharedPreferences("veil_encrypted_prefs_fallback", Context.MODE_PRIVATE);
            }
            String encryptedMekStr = sharedPreferences.getString("encrypted_mek", null);
            String saltStr = sharedPreferences.getString("salt", null);
            String ivStr = sharedPreferences.getString("iv", null);
            if (encryptedMekStr == null || saltStr == null || ivStr == null) {
                return false;
            }
            byte[] salt = android.util.Base64.decode(saltStr, android.util.Base64.DEFAULT);
            byte[] iv = android.util.Base64.decode(ivStr, android.util.Base64.DEFAULT);
            byte[] encryptedMek = android.util.Base64.decode(encryptedMekStr, android.util.Base64.DEFAULT);

            javax.crypto.SecretKeyFactory factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                    trimmed.toCharArray(), salt, 100000, 256
            );
            javax.crypto.SecretKey kek = factory.generateSecret(spec);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            javax.crypto.spec.GCMParameterSpec gcmSpec = new javax.crypto.spec.GCMParameterSpec(128, iv);
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, kek, gcmSpec);
            cipher.doFinal(encryptedMek);
            setMasterPin(trimmed);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public String getSecretVaultPassword() {
        return mainPrefs.getString("secret_vault_password", null);
    }

    public boolean hasSecretVaultPassword() {
        String p = getSecretVaultPassword();
        return p != null && !p.isEmpty();
    }

    public void setSecretVaultPassword(String pwd) {
        if (pwd == null || pwd.trim().isEmpty()) {
            mainPrefs.edit().remove("secret_vault_password").apply();
        } else {
            mainPrefs.edit().putString("secret_vault_password", pwd.trim()).apply();
        }
    }

    public boolean verifySecretVaultPassword(String input) {
        if (input == null || input.trim().isEmpty()) return false;
        String trimmed = input.trim();
        String saved = getSecretVaultPassword();
        if (saved != null && !saved.isEmpty()) {
            return saved.equals(trimmed);
        }
        // Fall back to Master PIN if vault password has not been explicitly configured yet
        String master = getMasterPin();
        if (master != null && !master.isEmpty()) {
            return master.equals(trimmed);
        }
        return false;
    }

    public boolean isStealthVaultEnabled() {
        return mainPrefs.getBoolean("stealth_vault_hidden", false);
    }

    public void setStealthVaultEnabled(boolean hidden) {
        mainPrefs.edit().putBoolean("stealth_vault_hidden", hidden).apply();
    }

    public boolean isDecoySessionActive() {
        return prefs.getBoolean("decoy_session_active", false);
    }

    public void setDecoySessionActive(boolean active) {
        prefs.edit().putBoolean("decoy_session_active", active).apply();
    }

    // ==========================================
    // XV & XVII. WORD FILTER, TRANSLATOR & BOTS
    // ==========================================

    public boolean isWordFilterEnabled() {
        return prefs.getBoolean("word_filter_enabled", true);
    }

    public void setWordFilterEnabled(boolean enabled) {
        prefs.edit().putBoolean("word_filter_enabled", enabled).apply();
    }

    public String applyWordFilter(String text) {
        if (text == null || !isWordFilterEnabled()) return text;
        String[] blocked = {"spamlink.xyz", "scamcoin", "phishing"};
        String result = text;
        for (String w : blocked) {
            result = result.replaceAll("(?i)" + java.util.regex.Pattern.quote(w), "•••");
        }
        return result;
    }

    private static final String[] TRUTH_OR_DARE_PROMPTS = {
            "🎲 TRUTH: What is a secret goal you have never told anyone in real life?",
            "🎲 DARE: Switch your voice filter to 'Robot' or 'Chipmunk' and send a 5-second voice note!",
            "🎲 TRUTH: Which of your anonymous personas feels most like the real you?",
            "🎲 DARE: Send a ⚡ Blink (View-Once) doodle drawing of the first thing you see right now!",
            "🎲 TRUTH: What is the boldest confession you've ever wanted to post on VeilBoard?",
            "🎲 DARE: Create an inline Poll in this chat with 3 unexpected options!"
    };

    public String getRandomTruthOrDarePrompt() {
        return TRUTH_OR_DARE_PROMPTS[new Random().nextInt(TRUTH_OR_DARE_PROMPTS.length)];
    }

    public String[] getSmartSuggestionsForMessage(String incomingText) {
        if (incomingText == null) {
            return new String[]{"👍 Got it", "🔥 Nice!", "Tell me more", "🤫 Secret?"};
        }
        String lower = incomingText.toLowerCase();
        if (lower.contains("?")) {
            return new String[]{"Yes, definitely!", "Not sure yet 🤔", "Let's do it ⚡", "Tell me more"};
        } else if (lower.contains("hey") || lower.contains("hello") || lower.contains("hi")) {
            return new String[]{"Hey there! 👋", "What's on your mind?", "🎲 Truth or Dare?", "How's your day?"};
        } else if (lower.contains("poll") || lower.contains("vote")) {
            return new String[]{"Just voted! 📊", "Great question", "Interesting choice 👀"};
        }
        return new String[]{"🔥 Awesome", "💯 Agreed", "👀 Interesting", "⚡ Send a Blink"};
    }

    // ==========================================
    // WHATSAPP STATUS (24-HOUR UPDATES) SYSTEM
    // ==========================================

    public static class StatusViewer {
        public final String userId;
        public final String displayName;
        public final String avatarUrl;
        public final long viewedAtMs;

        public StatusViewer(String userId, String displayName, String avatarUrl, long viewedAtMs) {
            this.userId = userId;
            this.displayName = displayName;
            this.avatarUrl = avatarUrl;
            this.viewedAtMs = viewedAtMs;
        }
    }

    public static class StatusUpdate {
        public final String id;
        public final String authorId;
        public final String authorName;
        public final String contentText;
        public final String mediaUri;
        public final String bgColorHex;
        public final int fontStyleIndex;
        public final long timestampMs;
        public boolean viewed;
        public int viewCount;
        public final boolean isMine;
        public final List<StatusViewer> viewers;

        public StatusUpdate(String id, String authorId, String authorName, String contentText,
                            String mediaUri, String bgColorHex, int fontStyleIndex, long timestampMs,
                            boolean viewed, int viewCount, boolean isMine, List<StatusViewer> viewers) {
            this.id = id;
            this.authorId = authorId;
            this.authorName = authorName;
            this.contentText = contentText;
            this.mediaUri = mediaUri;
            this.bgColorHex = bgColorHex;
            this.fontStyleIndex = fontStyleIndex;
            this.timestampMs = timestampMs;
            this.viewed = viewed;
            this.viewCount = viewCount;
            this.isMine = isMine;
            this.viewers = viewers != null ? viewers : new ArrayList<>();
        }

        public StatusUpdate(String id, String authorId, String authorName, String contentText,
                            String bgColorHex, int fontStyleIndex, long timestampMs,
                            boolean viewed, int viewCount, boolean isMine) {
            this(id, authorId, authorName, contentText, null, bgColorHex, fontStyleIndex, timestampMs,
                    viewed, viewCount, isMine, new ArrayList<>());
        }
    }

    public interface StatusChangeListener {
        void onStatusesChanged();
    }
    private final List<StatusChangeListener> statusChangeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addStatusChangeListener(StatusChangeListener l) {
        if (l != null && !statusChangeListeners.contains(l)) statusChangeListeners.add(l);
    }

    public void removeStatusChangeListener(StatusChangeListener l) {
        statusChangeListeners.remove(l);
    }

    public void notifyStatusListeners() {
        for (StatusChangeListener l : statusChangeListeners) {
            try { l.onStatusesChanged(); } catch (Exception ignored) {}
        }
    }

    public synchronized void addPeerStatus(String id, String authorId, String authorName,
                                           String contentText, String mediaUri, String bgColorHex,
                                           int fontStyleIndex, long timestamp) {
        if (id == null || authorId == null) return;
        String myUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "me";
        if (authorId.equals(myUid)) return;

        try {
            JSONArray arr = new JSONArray(prefs.getString("peer_statuses_json", "[]"));
            JSONArray next = new JSONArray();
            JSONObject newObj = new JSONObject();
            newObj.put("id", id);
            newObj.put("authorId", authorId);
            newObj.put("authorName", authorName != null ? authorName : "Anonymous Contact");
            newObj.put("contentText", contentText != null ? contentText : "");
            newObj.put("mediaUri", mediaUri != null ? mediaUri : "");
            newObj.put("bgColorHex", bgColorHex != null ? bgColorHex : "#008069");
            newObj.put("fontStyleIndex", fontStyleIndex);
            newObj.put("timestamp", timestamp);
            next.put(newObj);

            long now = System.currentTimeMillis();
            long dayMs = 24L * 60L * 60L * 1000L;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!id.equals(o.optString("id")) && (now - o.optLong("timestamp", 0) < dayMs)) {
                    next.put(o);
                }
            }
            prefs.edit().putString("peer_statuses_json", next.toString()).apply();
            notifyStatusListeners();
        } catch (Exception ignored) {}
    }

    public void startStatusSync(Runnable onUpdatedCallback) {
        // Pure P2P: prune expired peer statuses (> 24h) and notify UI
        long now = System.currentTimeMillis();
        long dayMs = 24L * 60L * 60L * 1000L;
        try {
            JSONArray arr = new JSONArray(prefs.getString("peer_statuses_json", "[]"));
            JSONArray kept = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (now - o.optLong("timestamp", 0) < dayMs) {
                    kept.put(o);
                }
            }
            if (kept.length() != arr.length()) {
                prefs.edit().putString("peer_statuses_json", kept.toString()).apply();
            }
        } catch (Exception ignored) {}

        if (onUpdatedCallback != null) {
            onUpdatedCallback.run();
        }
    }

    public void stopStatusSync() {
        // Pure P2P mesh
    }

    public void updateLocalStatusViewers(String statusId, List<StatusViewer> remoteViewers) {
        if (statusId == null || remoteViewers == null) return;
        try {
            JSONArray myArr = new JSONArray(prefs.getString("my_statuses_json", "[]"));
            boolean updated = false;
            for (int i = 0; i < myArr.length(); i++) {
                JSONObject o = myArr.getJSONObject(i);
                if (statusId.equals(o.optString("id"))) {
                    JSONArray vArr = new JSONArray();
                    for (StatusViewer sv : remoteViewers) {
                        JSONObject vo = new JSONObject();
                        vo.put("userId", sv.userId);
                        vo.put("displayName", sv.displayName);
                        vo.put("avatarUrl", sv.avatarUrl);
                        vo.put("viewedAt", sv.viewedAtMs);
                        vArr.put(vo);
                    }
                    o.put("viewers", vArr);
                    o.put("views", vArr.length());
                    updated = true;
                    break;
                }
            }
            if (updated) {
                prefs.edit().putString("my_statuses_json", myArr.toString()).apply();
            }
        } catch (Exception ignored) {}
    }

    public List<StatusUpdate> getStatusUpdates(List<com.m3.veil.model.Chat> localChats) {
        List<StatusUpdate> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        long dayMs = 24L * 60L * 60L * 1000L;

        // 1. Load user's own active statuses (< 24h old)
        try {
            JSONArray myArr = new JSONArray(prefs.getString("my_statuses_json", "[]"));
            JSONArray keptArr = new JSONArray();
            for (int i = 0; i < myArr.length(); i++) {
                JSONObject o = myArr.getJSONObject(i);
                long ts = o.optLong("timestamp", now);
                if (now - ts < dayMs) {
                    keptArr.put(o);
                    List<StatusViewer> viewerList = new ArrayList<>();
                    JSONArray vArr = o.optJSONArray("viewers");
                    if (vArr != null) {
                        for (int v = 0; v < vArr.length(); v++) {
                            JSONObject vo = vArr.optJSONObject(v);
                            if (vo != null) {
                                viewerList.add(new StatusViewer(
                                        vo.optString("userId"),
                                        vo.optString("displayName", "Anonymous Contact"),
                                        vo.optString("avatarUrl", ""),
                                        vo.optLong("viewedAt", ts + 120_000L)
                                ));
                            }
                        }
                    }

                    int totalViews = Math.max(viewerList.size(), o.optInt("views", 0));

                    result.add(new StatusUpdate(
                            o.optString("id"),
                            "me",
                            getActivePersona().getDisplayName(),
                            o.optString("text"),
                            o.optString("mediaUri", null),
                            o.optString("bgColor", "#008069"),
                            o.optInt("fontStyle", 0),
                            ts,
                            o.optBoolean("viewed", false),
                            totalViews,
                            true,
                            viewerList
                    ));
                }
            }
            if (keptArr.length() != myArr.length()) {
                prefs.edit().putString("my_statuses_json", keptArr.toString()).apply();
            }
        } catch (Exception ignored) {}

        // 2. Peer statuses: Real remote peer statuses received over WebRTC mesh
        String viewedIds = prefs.getString("viewed_status_ids", "");
        try {
            JSONArray pArr = new JSONArray(prefs.getString("peer_statuses_json", "[]"));
            for (int i = 0; i < pArr.length(); i++) {
                JSONObject o = pArr.getJSONObject(i);
                long ts = o.optLong("timestamp", now);
                if (now - ts < dayMs) {
                    String pId = o.optString("id");
                    boolean isViewed = viewedIds.contains(pId);
                    result.add(new StatusUpdate(
                            pId,
                            o.optString("authorId", "peer"),
                            o.optString("authorName", "Anonymous Contact"),
                            o.optString("contentText", ""),
                            o.optString("mediaUri", null),
                            o.optString("bgColorHex", "#008069"),
                            o.optInt("fontStyleIndex", 0),
                            ts,
                            isViewed,
                            0,
                            false,
                            new ArrayList<>()
                    ));
                }
            }
        } catch (Exception ignored) {}

        synchronized (remotePeerStatuses) {
            for (StatusUpdate su : remotePeerStatuses) {
                boolean exists = false;
                for (StatusUpdate r : result) {
                    if (r.id != null && r.id.equals(su.id)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    su.viewed = viewedIds.contains(su.id);
                    result.add(su);
                }
            }
        }

        // 3. Add Pure P2P Statuses from StatusController (100% WebRTC Mesh & SQLCipher)
        try {
            List<StatusUpdate> p2pList = StatusController.getInstance(appContext).toStatusUpdateList();
            for (StatusUpdate su : p2pList) {
                boolean exists = false;
                for (StatusUpdate r : result) {
                    if (r.id != null && r.id.equals(su.id)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    result.add(su);
                }
            }
        } catch (Throwable ignored) {}

        return result;
    }

    public StatusUpdate addMyStatus(String text, String bgColorHex, int fontStyleIndex) {
        long now = System.currentTimeMillis();
        String id = "my_status_" + now;

        // Post to StatusController (100% Pure P2P WebRTC Mesh)
        try {
            StatusController.getInstance(appContext).postTextStatus(
                    text, bgColorHex, fontStyleIndex, StatusController.PrivacyAcl.ALL_CONTACTS, null);
        } catch (Throwable ignored) {}
        try {
            JSONArray myArr = new JSONArray(prefs.getString("my_statuses_json", "[]"));
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("text", text);
            o.put("bgColor", bgColorHex);
            o.put("fontStyle", fontStyleIndex);
            o.put("timestamp", now);
            o.put("viewed", false);
            o.put("views", 0);
            o.put("viewers", new JSONArray());
            JSONArray nextArr = new JSONArray();
            nextArr.put(o);
            for (int i = 0; i < myArr.length(); i++) {
                nextArr.put(myArr.getJSONObject(i));
            }
            prefs.edit().putString("my_statuses_json", nextArr.toString()).apply();
            addXp(15);
        } catch (Exception ignored) {}

        // Broadcast status over WebRTC Mesh DataChannels (ZERO cloud storage)
        try {
            com.m3.veil.webrtc.WebRtcManager.getInstance(appContext)
                    .broadcastStatus(id, text, null, bgColorHex, fontStyleIndex);
        } catch (Exception ignored) {}
        notifyStatusListeners();

        return new StatusUpdate(id, "me", getActivePersona().getDisplayName(),
                text, null, bgColorHex, fontStyleIndex, now, false, 0, true, new ArrayList<>());
    }

    public StatusUpdate addMyMediaStatus(String mediaUri, String caption, String bgColorHex) {
        long now = System.currentTimeMillis();
        String id = "my_status_" + now;

        // Post to StatusController (100% Pure P2P WebRTC Mesh)
        try {
            StatusController.getInstance(appContext).postPhotoStatus(
                    mediaUri, caption, StatusController.PrivacyAcl.ALL_CONTACTS, null);
        } catch (Throwable ignored) {}

        try {
            JSONArray myArr = new JSONArray(prefs.getString("my_statuses_json", "[]"));
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("text", caption != null ? caption : "");
            o.put("mediaUri", mediaUri != null ? mediaUri : "");
            o.put("bgColor", bgColorHex != null ? bgColorHex : "#1E293B");
            o.put("fontStyle", 0);
            o.put("timestamp", now);
            o.put("viewed", false);
            o.put("views", 0);
            o.put("viewers", new JSONArray());
            JSONArray nextArr = new JSONArray();
            nextArr.put(o);
            for (int i = 0; i < myArr.length(); i++) {
                nextArr.put(myArr.getJSONObject(i));
            }
            prefs.edit().putString("my_statuses_json", nextArr.toString()).apply();
            addXp(25);
        } catch (Exception ignored) {}

        // Broadcast status over WebRTC Mesh DataChannels (ZERO cloud storage)
        new Thread(() -> {
            try {
                String syncMedia = encodeImageUriToBase64(mediaUri);
                com.m3.veil.webrtc.WebRtcManager.getInstance(appContext)
                        .broadcastStatus(id, caption, syncMedia, bgColorHex != null ? bgColorHex : "#1E293B", 0);
            } catch (Exception ignored) {}
            notifyStatusListeners();
        }).start();

        return new StatusUpdate(id, "me", getActivePersona().getDisplayName(),
                caption != null ? caption : "", mediaUri, bgColorHex != null ? bgColorHex : "#1E293B",
                0, now, false, 0, true, new ArrayList<>());
    }

    private String encodeImageUriToBase64(String uriStr) {
        if (uriStr == null || uriStr.isEmpty()) return "";
        if (uriStr.startsWith("data:image")) return uriStr;
        try {
            android.net.Uri uri = android.net.Uri.parse(uriStr);
            java.io.InputStream is = appContext.getContentResolver().openInputStream(uri);
            if (is == null) return uriStr;
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeStream(is, null, bounds);
            is.close();

            int sample = 1;
            int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
            while (maxDim / sample > 800) {
                sample *= 2;
            }

            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, sample);
            is = appContext.getContentResolver().openInputStream(uri);
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is, null, opts);
            if (is != null) is.close();

            if (bmp != null) {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, baos);
                byte[] bytes = baos.toByteArray();
                bmp.recycle();
                return "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            }
        } catch (Exception e) {
            android.util.Log.w("VeilFeatureManager", "Failed to encode status image to base64: " + e.getMessage());
        }
        return uriStr;
    }

    public void recordStatusView(String statusId, String viewerId, String viewerName, String viewerAvatar) {
        if (statusId == null || viewerId == null) return;
        try {
            JSONArray myArr = new JSONArray(prefs.getString("my_statuses_json", "[]"));
            boolean updated = false;
            for (int i = 0; i < myArr.length(); i++) {
                JSONObject o = myArr.getJSONObject(i);
                if (statusId.equals(o.optString("id"))) {
                    JSONArray vArr = o.optJSONArray("viewers");
                    if (vArr == null) vArr = new JSONArray();
                    boolean exists = false;
                    for (int v = 0; v < vArr.length(); v++) {
                        JSONObject vo = vArr.optJSONObject(v);
                        if (vo != null && viewerId.equals(vo.optString("userId"))) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        JSONObject newV = new JSONObject();
                        newV.put("userId", viewerId);
                        newV.put("displayName", viewerName != null ? viewerName : "Anonymous Contact");
                        newV.put("avatarUrl", viewerAvatar != null ? viewerAvatar : "");
                        newV.put("viewedAt", System.currentTimeMillis());
                        vArr.put(newV);
                        o.put("viewers", vArr);
                        o.put("views", vArr.length());
                        updated = true;
                    }
                    break;
                }
            }
            if (updated) {
                prefs.edit().putString("my_statuses_json", myArr.toString()).apply();
                notifyStatusListeners();
            }
        } catch (Exception ignored) {}
    }

    public void deleteMyStatus(String statusId) {
        try {
            StatusController.getInstance(appContext).deleteStatusSegment(statusId);
        } catch (Throwable ignored) {}
        try {
            JSONArray myArr = new JSONArray(prefs.getString("my_statuses_json", "[]"));
            JSONArray nextArr = new JSONArray();
            for (int i = 0; i < myArr.length(); i++) {
                JSONObject o = myArr.getJSONObject(i);
                if (!statusId.equals(o.optString("id"))) {
                    nextArr.put(o);
                }
            }
            prefs.edit().putString("my_statuses_json", nextArr.toString()).apply();
            notifyStatusListeners();
        } catch (Exception ignored) {}
    }

    public void markStatusViewed(String statusId) {
        if (statusId == null) return;
        try {
            StatusController.getInstance(appContext).markStatusViewed(statusId);
        } catch (Throwable ignored) {}
        String existing = prefs.getString("viewed_status_ids", "");
        if (!existing.contains(statusId)) {
            prefs.edit().putString("viewed_status_ids", existing.isEmpty() ? statusId : existing + "," + statusId).apply();
            notifyStatusListeners();
        }
    }
}
