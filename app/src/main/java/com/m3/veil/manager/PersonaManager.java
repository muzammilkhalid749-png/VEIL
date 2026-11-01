package com.m3.veil.manager;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.model.Persona;
import com.m3.veil.utils.UserPreferences;
import com.m3.veil.webrtc.WebRtcManager;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Centralized Global Persona Session Manager (PersonaContext).
 * Enforces SQLite storage isolation, unique usernames per persona,
 * paywall gating for Persona 3+, and broadcasts global identity-switch events
 * across all Activities, Fragments, Services, and P2P controllers.
 */
public class PersonaManager {

    private static final String TAG = "PersonaManager";
    public static final String ACTION_PERSONA_SWITCHED = "com.m3.veil.ACTION_PERSONA_SWITCHED";
    public static final String EXTRA_PERSONA_ID = "extra_persona_id";
    public static final String EXTRA_PERSONA_NAME = "extra_persona_name";
    public static final String EXTRA_PERSONA_USERNAME = "extra_persona_username";
    public static final String EXTRA_PERSONA_KEY_PAIR_ID = "extra_persona_key_pair_id";

    private static final String PREF_NAME = "VeilFeaturePrefs";
    private static final String KEY_PERSONAS = "personas";
    private static final String KEY_ACTIVE_PERSONA = "active_persona_id";

    public static final String PRIMARY_PERSONA_ID = "persona_primary";
    public static final String GHOST_PERSONA_ID = "persona_ghost";
    public static final String NEON_PERSONA_ID = "persona_neon";

    public interface PersonaSwitchListener {
        void onPersonaSwitched(Persona newActivePersona);
    }

    private static volatile PersonaManager instance;
    private final Context appContext;
    private final SharedPreferences prefs;
    private final Gson gson = new Gson();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<PersonaSwitchListener> listeners = new CopyOnWriteArrayList<>();

    public static synchronized PersonaManager getInstance(Context context) {
        if (instance == null) {
            instance = new PersonaManager(context.getApplicationContext());
        }
        return instance;
    }

    private PersonaManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = this.appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        ensureInitializedAndRepaired();
    }

    public void addPersonaSwitchListener(PersonaSwitchListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removePersonaSwitchListener(PersonaSwitchListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    /**
     * Ensures Persona 1, Persona 2, and Persona 3 exist in the master SQLite database
     * with distinct display names, unique usernames, isolated keyPairIds, and repaired metadata.
     */
    public synchronized void ensureInitializedAndRepaired() {
        try {
            EncryptedChatDatabase masterDb = EncryptedChatDatabase.getMasterDatabase(appContext);
            UserPreferences userPrefs = new UserPreferences(appContext);
            String baseUid = userPrefs.getUserId();
            if (TextUtils.isEmpty(baseUid)) {
                baseUid = "sovereign_node";
            }

            String primaryDisplayName = userPrefs.getDisplayName();
            String primaryUsername = userPrefs.getUsername();
            if (TextUtils.isEmpty(primaryDisplayName) || "User".equalsIgnoreCase(primaryDisplayName) || "ShadowVeil".equalsIgnoreCase(primaryDisplayName)) {
                if (!TextUtils.isEmpty(primaryUsername) && !"ShadowVeil".equalsIgnoreCase(primaryUsername)) {
                    primaryDisplayName = primaryUsername;
                } else {
                    primaryDisplayName = "Sovereign Node";
                }
            }
            if (TextUtils.isEmpty(primaryUsername) || "ShadowVeil".equalsIgnoreCase(primaryUsername)) {
                primaryUsername = Persona.normalizeUsername(primaryDisplayName);
            } else {
                primaryUsername = Persona.normalizeUsername(primaryUsername);
            }
            String primaryBio = userPrefs.getBio();
            if (TextUtils.isEmpty(primaryBio)) {
                primaryBio = "Encrypted by default. Tracked by no one.";
            }
            String primaryAvatar = userPrefs.getAvatarPath();

            List<Persona> dbPersonas = masterDb.getAllPersonasFromDb();
            if (dbPersonas.isEmpty()) {
                // Migrate from legacy JSON if present, or seed defaults
                String json = prefs.getString(KEY_PERSONAS, null);
                List<Persona> legacyList = null;
                if (!TextUtils.isEmpty(json)) {
                    try {
                        Type type = new TypeToken<ArrayList<Persona>>() {}.getType();
                        legacyList = gson.fromJson(json, type);
                    } catch (Exception ignored) {}
                }

                String savedActiveId = prefs.getString(KEY_ACTIVE_PERSONA, PRIMARY_PERSONA_ID);
                if (TextUtils.isEmpty(savedActiveId) || "default".equals(savedActiveId)) {
                    savedActiveId = PRIMARY_PERSONA_ID;
                }

                // If active persona is Persona 3+ and user is not premium, revert active to primary
                if (!BillingManager.getInstance(appContext).isPremiumActive() && NEON_PERSONA_ID.equals(savedActiveId)) {
                    savedActiveId = PRIMARY_PERSONA_ID;
                    prefs.edit().putString(KEY_ACTIVE_PERSONA, PRIMARY_PERSONA_ID).apply();
                }

                List<Persona> toSeed = new ArrayList<>();
                Persona p1 = new Persona(
                        PRIMARY_PERSONA_ID,
                        primaryDisplayName,
                        primaryUsername,
                        primaryAvatar != null ? primaryAvatar : "default_1",
                        primaryBio,
                        baseUid,
                        PRIMARY_PERSONA_ID.equals(savedActiveId),
                        "Focused",
                        0xFF7C4DFF,
                        false,
                        0L
                );
                toSeed.add(p1);

                // Preserve custom edits on legacy personas ONLY if created by user (never auto-seed dummy personas)
                if (legacyList != null) {
                    for (Persona legacy : legacyList) {
                        if (legacy == null || TextUtils.isEmpty(legacy.getId())) continue;
                        if (PRIMARY_PERSONA_ID.equals(legacy.getId())) continue;
                        // Skip unedited dummy seed personas
                        if (GHOST_PERSONA_ID.equals(legacy.getId()) && "Midnight Drifter".equalsIgnoreCase(legacy.getDisplayName())) {
                            continue;
                        }
                        if (NEON_PERSONA_ID.equals(legacy.getId()) && "Cyber Pulse".equalsIgnoreCase(legacy.getDisplayName())) {
                            continue;
                        }
                        // User-created persona
                        if (TextUtils.isEmpty(legacy.getUsername()) || legacy.getUsername().equalsIgnoreCase(primaryUsername)) {
                            legacy.setUsername(Persona.normalizeUsername(legacy.getDisplayName()) + "_" + legacy.getId().substring(Math.max(0, legacy.getId().length() - 4)));
                        }
                        if (TextUtils.isEmpty(legacy.getKeyPairId())) {
                            legacy.setKeyPairId(baseUid + "_" + legacy.getId());
                        }
                        legacy.setActive(legacy.getId().equals(savedActiveId));
                        toSeed.add(legacy);
                    }
                }

                for (Persona p : toSeed) {
                    masterDb.upsertPersonaInDb(p);
                }
                dbPersonas = masterDb.getAllPersonasFromDb();
            }

            // Prune any legacy unedited auto-seeded dummy personas (Midnight Drifter / Cyber Pulse)
            List<Persona> filteredPersonas = new ArrayList<>();
            for (Persona p : dbPersonas) {
                if (p == null) continue;
                if (GHOST_PERSONA_ID.equals(p.getId()) && "Midnight Drifter".equalsIgnoreCase(p.getDisplayName()) && "midnight_drifter".equalsIgnoreCase(p.getUsername())) {
                    masterDb.deletePersonaFromDb(p.getId());
                    continue;
                }
                if (NEON_PERSONA_ID.equals(p.getId()) && "Cyber Pulse".equalsIgnoreCase(p.getDisplayName()) && "cyber_pulse".equalsIgnoreCase(p.getUsername())) {
                    masterDb.deletePersonaFromDb(p.getId());
                    continue;
                }
                filteredPersonas.add(p);
            }
            dbPersonas = filteredPersonas;

            // Repair pass: guarantee Persona 2 and Persona 3 never duplicate Persona 1's display_name or username
            boolean modified = false;
            boolean hasActive = false;
            String savedActiveId = prefs.getString(KEY_ACTIVE_PERSONA, PRIMARY_PERSONA_ID);
            if ("default".equals(savedActiveId) || TextUtils.isEmpty(savedActiveId)) {
                savedActiveId = PRIMARY_PERSONA_ID;
            }

            for (int i = 0; i < dbPersonas.size(); i++) {
                Persona p = dbPersonas.get(i);
                if (PRIMARY_PERSONA_ID.equals(p.getId())) {
                    if (TextUtils.isEmpty(p.getKeyPairId())) {
                        p.setKeyPairId(baseUid);
                        modified = true;
                    }
                    // Keep Persona 1 synced with primary UserPreferences if Persona 1 is still default placeholder
                    if (("Sovereign Node".equals(p.getDisplayName()) || "User".equals(p.getDisplayName()))
                            && !TextUtils.isEmpty(primaryDisplayName) && !"Sovereign Node".equals(primaryDisplayName)) {
                        p.setDisplayName(primaryDisplayName);
                        modified = true;
                    }
                    if (("sovereign_node".equals(p.getUsername()) || "user".equals(p.getUsername()))
                            && !TextUtils.isEmpty(primaryUsername) && !"sovereign_node".equals(primaryUsername)) {
                        p.setUsername(primaryUsername);
                        modified = true;
                    }
                } else {
                    if (TextUtils.isEmpty(p.getKeyPairId()) || p.getKeyPairId().equals(baseUid)) {
                        p.setKeyPairId(baseUid + "_" + p.getId());
                        modified = true;
                    }
                    if (p.getUsername() != null && p.getUsername().equalsIgnoreCase(primaryUsername)) {
                        p.setUsername(Persona.normalizeUsername(p.getDisplayName()) + "_" + (i + 1));
                        modified = true;
                    }
                }

                // Enforce paywall lock on startup if non-premium had Persona 3+ active
                if (i >= BillingManager.MAX_FREE_PERSONAS && !BillingManager.getInstance(appContext).isPremiumActive() && p.isActive()) {
                    p.setActive(false);
                    savedActiveId = PRIMARY_PERSONA_ID;
                    modified = true;
                }

                if (p.isActive()) {
                    hasActive = true;
                    savedActiveId = p.getId();
                }

                if (modified) {
                    masterDb.upsertPersonaInDb(p);
                }
            }

            if (!hasActive && !dbPersonas.isEmpty()) {
                masterDb.setActivePersonaInDb(savedActiveId);
                dbPersonas = masterDb.getAllPersonasFromDb();
            }

            syncPrefsMirror(dbPersonas, savedActiveId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize PersonaManager", e);
        }
    }

    private boolean isDuplicateOfPrimary(Persona candidate, String primaryDisplayName, String primaryUsername) {
        if (candidate == null) return true;
        String cName = candidate.getDisplayName() != null ? candidate.getDisplayName().trim() : "";
        String cUser = candidate.getUsername() != null ? candidate.getUsername().trim() : "";
        if (TextUtils.isEmpty(cName) || TextUtils.isEmpty(cUser)) return true;
        if (!TextUtils.isEmpty(primaryDisplayName) && cName.equalsIgnoreCase(primaryDisplayName.trim())) {
            return true;
        }
        if (!TextUtils.isEmpty(primaryUsername) && cUser.equalsIgnoreCase(primaryUsername.trim())) {
            return true;
        }
        return false;
    }

    private void syncPrefsMirror(List<Persona> personas, String activeId) {
        try {
            prefs.edit()
                    .putString(KEY_PERSONAS, gson.toJson(personas))
                    .putString(KEY_ACTIVE_PERSONA, activeId != null ? activeId : PRIMARY_PERSONA_ID)
                    .apply();
        } catch (Exception ignored) {}
    }

    public synchronized List<Persona> getPersonas() {
        EncryptedChatDatabase masterDb = EncryptedChatDatabase.getMasterDatabase(appContext);
        List<Persona> list = masterDb.getAllPersonasFromDb();
        if (list.isEmpty()) {
            ensureInitializedAndRepaired();
            list = masterDb.getAllPersonasFromDb();
        }
        return list;
    }

    public synchronized Persona getActivePersona() {
        List<Persona> list = getPersonas();
        String savedId = prefs.getString(KEY_ACTIVE_PERSONA, PRIMARY_PERSONA_ID);
        if ("default".equals(savedId) || TextUtils.isEmpty(savedId)) {
            savedId = PRIMARY_PERSONA_ID;
        }
        for (int i = 0; i < list.size(); i++) {
            Persona p = list.get(i);
            if (p.isActive() || p.getId().equals(savedId)) {
                if (i >= BillingManager.MAX_FREE_PERSONAS && !BillingManager.getInstance(appContext).isPremiumActive()) {
                    continue;
                }
                return p;
            }
        }
        return list.isEmpty() ? new Persona(PRIMARY_PERSONA_ID, "Sovereign Node", "sovereign_node", "default_1", "Encrypted by default.", "sovereign_node", true, "Focused", 0xFF7C4DFF, false, 0L) : list.get(0);
    }

    public synchronized String getActivePersonaId() {
        Persona active = getActivePersona();
        return active != null ? active.getId() : PRIMARY_PERSONA_ID;
    }

    /**
     * Returns the P2P/Signaling/Firestore identity key for the currently active persona.
     * Persona 1 uses the primary UID; Persona 2+ uses its isolated keyPairId so chats/groups never collide.
     */
    public synchronized String getActivePersonaPeerId() {
        return getPersonaPeerId(getActivePersona());
    }

    /**
     * Returns the canonical P2P/Firestore peerId for any given Persona.
     */
    public synchronized String getPersonaPeerId(Persona persona) {
        UserPreferences userPrefs = new UserPreferences(appContext);
        String baseUid = userPrefs.getUserId();
        if (TextUtils.isEmpty(baseUid)) {
            try {
                if (com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null) {
                    baseUid = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser().getUid();
                }
            } catch (Throwable ignored) {}
        }
        if (persona == null || PRIMARY_PERSONA_ID.equals(persona.getId())) {
            return !TextUtils.isEmpty(baseUid) ? baseUid : (persona != null && !TextUtils.isEmpty(persona.getKeyPairId()) ? persona.getKeyPairId() : "sovereign_node");
        }
        if (!TextUtils.isEmpty(persona.getKeyPairId()) && !persona.getKeyPairId().equals(baseUid)) {
            return persona.getKeyPairId();
        }
        return (!TextUtils.isEmpty(baseUid) ? baseUid : "sovereign_node") + "_" + persona.getId();
    }

    /**
     * Finds a local Persona on this device matching the given peerId / userId / keyPairId, or null if external peer.
     */
    public synchronized Persona findLocalPersonaByPeerId(String peerOrUserId) {
        if (TextUtils.isEmpty(peerOrUserId)) return null;
        String clean = peerOrUserId.trim();
        List<Persona> list = getPersonas();
        UserPreferences userPrefs = new UserPreferences(appContext);
        String baseUid = userPrefs.getUserId();
        String firebaseUid = null;
        try {
            if (com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null) {
                firebaseUid = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser().getUid();
            }
        } catch (Throwable ignored) {}

        // 1. Check secondary personas first (most specific, e.g. contains _persona_)
        for (Persona p : list) {
            if (p == null || PRIMARY_PERSONA_ID.equals(p.getId())) continue;
            String pPeerId = getPersonaPeerId(p);
            if (clean.equals(p.getId())
                    || clean.equals(pPeerId)
                    || (!TextUtils.isEmpty(p.getKeyPairId()) && clean.equals(p.getKeyPairId()))
                    || clean.endsWith("_" + p.getId())) {
                return p;
            }
        }

        // 2. Check primary persona (only if clean does NOT contain a secondary _persona_ suffix)
        if (!clean.contains("_persona_")) {
            for (Persona p : list) {
                if (p != null && PRIMARY_PERSONA_ID.equals(p.getId())) {
                    String pPeerId = getPersonaPeerId(p);
                    if (clean.equals(PRIMARY_PERSONA_ID)
                            || clean.equals(pPeerId)
                            || (!TextUtils.isEmpty(baseUid) && clean.equals(baseUid))
                            || (!TextUtils.isEmpty(firebaseUid) && clean.equals(firebaseUid))
                            || (!TextUtils.isEmpty(p.getKeyPairId()) && clean.equals(p.getKeyPairId()))) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Strictly checks whether candidateId belongs to the CURRENTLY ACTIVE persona ONLY.
     * Other personas belonging to the same user on this device return FALSE so they are treated as completely separate peer identities.
     */
    public synchronized boolean isSelfForActivePersona(String candidateId) {
        if (TextUtils.isEmpty(candidateId)) return false;
        String clean = candidateId.trim();
        if ("me".equalsIgnoreCase(clean)) return true;

        Persona active = getActivePersona();
        String activeId = active != null ? active.getId() : PRIMARY_PERSONA_ID;
        String activePeerId = getPersonaPeerId(active);

        if (clean.equals(activeId) || clean.equals(activePeerId)) {
            return true;
        }
        if (active != null && !TextUtils.isEmpty(active.getKeyPairId()) && clean.equals(active.getKeyPairId())) {
            return true;
        }

        if (!PRIMARY_PERSONA_ID.equals(activeId)) {
            // Secondary persona is active: ONLY IDs matching this secondary persona are self!
            return clean.endsWith("_" + activeId);
        } else {
            // Primary persona is active: any ID referencing a secondary persona is NOT self!
            if (clean.contains("_persona_") || (clean.startsWith("persona_") && !PRIMARY_PERSONA_ID.equals(clean))) {
                return false;
            }
            UserPreferences userPrefs = new UserPreferences(appContext);
            String prefUid = userPrefs.getUserId();
            if (!TextUtils.isEmpty(prefUid) && clean.equals(prefUid)) {
                return true;
            }
            try {
                if (com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null) {
                    String fbUid = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser().getUid();
                    if (!TextUtils.isEmpty(fbUid) && clean.equals(fbUid)) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
            return false;
        }
    }

    /**
     * Publishes a specific Persona as a standalone user entry in Firestore (`users/{peerId}` and `usernames/{username}`)
     * and caches its SovereignProfileManifest locally so personas can discover and message each other as distinct identities.
     */
    public void publishPersonaToCloudDirectory(Persona persona) {
        if (persona == null) return;
        final String peerId = getPersonaPeerId(persona);
        if (TextUtils.isEmpty(peerId)) return;
        final String username = Persona.normalizeUsername(
                !TextUtils.isEmpty(persona.getUsername()) ? persona.getUsername() : persona.getDisplayName());
        final String displayName = !TextUtils.isEmpty(persona.getDisplayName()) ? persona.getDisplayName() : username;
        final String bio = persona.getBio() != null ? persona.getBio() : "";
        final String avatar = persona.getAvatarUri() != null ? persona.getAvatarUri() : "default_1";

        new Thread(() -> {
            try {
                String pubKey = com.m3.veil.utils.EncryptionManager.getPublicKeyString();
                java.util.Map<String, Object> userDoc = new java.util.HashMap<>();
                userDoc.put("userId", peerId);
                userDoc.put("uid", peerId);
                userDoc.put("username", username);
                userDoc.put("usernameLower", username.toLowerCase(java.util.Locale.ROOT));
                userDoc.put("displayName", displayName);
                userDoc.put("bio", bio);
                userDoc.put("avatar", avatar);
                userDoc.put("personaId", persona.getId());
                userDoc.put("online", persona.getId().equals(getActivePersonaId()));
                userDoc.put("lastSeen", com.google.firebase.Timestamp.now());
                if (!TextUtils.isEmpty(pubKey)) {
                    userDoc.put("publicKey", pubKey);
                }
                com.google.firebase.firestore.FirebaseFirestore db = com.google.firebase.firestore.FirebaseFirestore.getInstance();
                db.collection("users").document(peerId)
                        .set(userDoc, com.google.firebase.firestore.SetOptions.merge());

                if (!TextUtils.isEmpty(username)) {
                    java.util.Map<String, Object> unameDoc = new java.util.HashMap<>();
                    unameDoc.put("userId", peerId);
                    unameDoc.put("uid", peerId);
                    unameDoc.put("username", username);
                    unameDoc.put("displayName", displayName);
                    if (!TextUtils.isEmpty(pubKey)) {
                        unameDoc.put("publicKey", pubKey);
                    }
                    db.collection("usernames").document(username.toLowerCase(java.util.Locale.ROOT))
                            .set(unameDoc, com.google.firebase.firestore.SetOptions.merge());
                }
            } catch (Throwable ignored) {}
        }).start();
    }

    public synchronized int getPersonaSlotIndex(String personaId) {
        List<Persona> list = getPersonas();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId().equals(personaId)) {
                return i;
            }
        }
        return 0;
    }

    public synchronized boolean isPersonaLockedByPaywall(String personaId) {
        int slotIndex = getPersonaSlotIndex(personaId);
        return !BillingManager.getInstance(appContext).canAccessPersonaSlot(slotIndex);
    }

    public synchronized boolean isUsernameTaken(String username, String excludePersonaId) {
        return EncryptedChatDatabase.getMasterDatabase(appContext)
                .isPersonaUsernameTakenInDb(Persona.normalizeUsername(username), excludePersonaId);
    }

    /**
     * Creates or updates a persona in the master SQLite table, enforcing unique username constraints.
     */
    public synchronized Persona getPersona(String personaId) {
        if (personaId == null) return null;
        for (Persona p : getPersonas()) {
            if (p != null && personaId.equals(p.getId())) return p;
        }
        return null;
    }

    public synchronized boolean updatePersona(Persona persona) {
        return saveOrUpdatePersona(persona);
    }

    public synchronized boolean saveOrUpdatePersona(Persona persona) {
        if (persona == null || TextUtils.isEmpty(persona.getId())) return false;
        EncryptedChatDatabase masterDb = EncryptedChatDatabase.getMasterDatabase(appContext);

        boolean isExisting = false;
        List<Persona> currentList = masterDb.getAllPersonasFromDb();
        for (Persona existingP : currentList) {
            if (existingP != null && persona.getId().equals(existingP.getId())) {
                isExisting = true;
                break;
            }
        }
        if (!isExisting && !PRIMARY_PERSONA_ID.equals(persona.getId())) {
            if (!BillingManager.getInstance(appContext).canCreateNewPersona(currentList.size())) {
                return false;
            }
        }

        String normalizedUsername = Persona.normalizeUsername(
                !TextUtils.isEmpty(persona.getUsername()) ? persona.getUsername() : persona.getDisplayName()
        );
        if (masterDb.isPersonaUsernameTakenInDb(normalizedUsername, persona.getId())) {
            normalizedUsername = normalizedUsername + "_" + Math.abs(persona.getId().hashCode() % 900 + 100);
        }
        persona.setUsername(normalizedUsername);

        if (TextUtils.isEmpty(persona.getKeyPairId())) {
            UserPreferences up = new UserPreferences(appContext);
            String baseUid = up.getUserId();
            if (PRIMARY_PERSONA_ID.equals(persona.getId())) {
                persona.setKeyPairId(!TextUtils.isEmpty(baseUid) ? baseUid : PRIMARY_PERSONA_ID);
            } else {
                persona.setKeyPairId((!TextUtils.isEmpty(baseUid) ? baseUid : "node") + "_" + persona.getId());
            }
        }

        boolean ok = masterDb.upsertPersonaInDb(persona);
        if (!PRIMARY_PERSONA_ID.equals(persona.getId()) && ok) {
            BillingManager.getInstance(appContext).markSecondPersonaSlotUsed();
        }
        if (PRIMARY_PERSONA_ID.equals(persona.getId())) {
            // Also keep primary UserPreferences aligned when editing Persona 1
            UserPreferences userPrefs = new UserPreferences(appContext);
            userPrefs.saveProfileFields(
                    userPrefs.getUserId(),
                    persona.getUsername(),
                    persona.getDisplayName(),
                    persona.getBio(),
                    persona.getAvatarUri()
            );
        }

        List<Persona> updated = masterDb.getAllPersonasFromDb();
        syncPrefsMirror(updated, getActivePersonaId());

        // Publish this persona as a distinct discoverable identity in Firestore & cloud manifest
        publishPersonaToCloudDirectory(persona);
        try {
            com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(appContext).ensureCurrentUserPublished();
        } catch (Throwable ignored) {}

        return ok;
    }

    /**
     * Permanently deletes a non-primary persona from SQLite and preferences.
     * Prevents deletion of PRIMARY_PERSONA_ID.
     * Marks the second persona slot as permanently used/deleted so it cannot be recreated.
     */
    public synchronized boolean deletePersona(String personaId) {
        if (personaId == null || PRIMARY_PERSONA_ID.equals(personaId) || "default".equals(personaId)) {
            return false;
        }
        BillingManager.getInstance(appContext).markSecondPersonaDeleted();
        EncryptedChatDatabase masterDb = EncryptedChatDatabase.getMasterDatabase(appContext);
        masterDb.deletePersonaFromDb(personaId);

        String activeId = getActivePersonaId();
        if (personaId.equals(activeId)) {
            switchPersona(PRIMARY_PERSONA_ID);
        } else {
            List<Persona> remaining = masterDb.getAllPersonasFromDb();
            syncPrefsMirror(remaining, getActivePersonaId());
        }

        try {
            com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(appContext).ensureCurrentUserPublished();
        } catch (Throwable ignored) {}

        return true;
    }

    /**
     * Serializes all local personas into a JSON array for cloud backup in SovereignProfileManifest.
     */
    public synchronized String exportPersonasJson() {
        List<Persona> list = getPersonas();
        try {
            return gson.toJson(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * Re-hydrates personas when user relogins with 12-phrase word or phone number.
     * Ensures all personas are restored to SQLite master table with proper isolated keyPairIds.
     */
    public synchronized void restorePersonasFromJson(String json) {
        if (TextUtils.isEmpty(json) || "[]".equals(json.trim())) return;
        try {
            Type type = new TypeToken<ArrayList<Persona>>() {}.getType();
            List<Persona> restored = gson.fromJson(json, type);
            if (restored == null || restored.isEmpty()) return;

            EncryptedChatDatabase masterDb = EncryptedChatDatabase.getMasterDatabase(appContext);
            UserPreferences userPrefs = new UserPreferences(appContext);
            String baseUid = userPrefs.getUserId();
            if (TextUtils.isEmpty(baseUid)) baseUid = "sovereign_node";

            for (Persona p : restored) {
                if (p == null || TextUtils.isEmpty(p.getId())) continue;
                if (TextUtils.isEmpty(p.getKeyPairId())) {
                    if (PRIMARY_PERSONA_ID.equals(p.getId())) {
                        p.setKeyPairId(baseUid);
                    } else {
                        p.setKeyPairId(baseUid + "_" + p.getId());
                    }
                }
                masterDb.upsertPersonaInDb(p);
                publishPersonaToCloudDirectory(p);
            }
            List<Persona> all = masterDb.getAllPersonasFromDb();
            syncPrefsMirror(all, getActivePersonaId());
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore personas from JSON", e);
        }
    }

    /**
     * Switches the active persona globally across the entire application.
     * Returns false if blocked by Paywall (Persona 3+ without Premium).
     */
    public synchronized boolean switchPersona(String targetPersonaId) {
        if (TextUtils.isEmpty(targetPersonaId) || "default".equals(targetPersonaId)) {
            targetPersonaId = PRIMARY_PERSONA_ID;
        }

        int slotIndex = getPersonaSlotIndex(targetPersonaId);
        if (!BillingManager.getInstance(appContext).canAccessPersonaSlot(slotIndex)) {
            Log.w(TAG, "Blocked switch to locked persona slot " + slotIndex + " (" + targetPersonaId + ")");
            return false;
        }

        EncryptedChatDatabase masterDb = EncryptedChatDatabase.getMasterDatabase(appContext);
        masterDb.setActivePersonaInDb(targetPersonaId);

        List<Persona> all = masterDb.getAllPersonasFromDb();
        syncPrefsMirror(all, targetPersonaId);

        Persona active = getActivePersona();
        for (Persona p : all) {
            publishPersonaToCloudDirectory(p);
        }

        // 1. Switch isolated SQLite vault + WHERE owner_persona_id filter
        EncryptedChatDatabase.switchActiveVault(appContext, active.getId());

        // 2. Reinitialize controllers for the new persona vault
        try {
            VeilMessagesController.getInstance(appContext).switchActivePersonaVault();
            VeilBoardController.getInstance(appContext).switchActivePersonaVault();
            StatusController.getInstance(appContext).reloadForActiveVault();
        } catch (Exception e) {
            Log.w(TAG, "Controller reload warning on persona switch", e);
        }

        // 3. Reinitialize P2P / WebRTC identity & signaling listener for the new persona
        try {
            String peerIdentity = getActivePersonaPeerId();
            WebRtcManager.getInstance(appContext).switchIdentity(peerIdentity);
        } catch (Exception e) {
            Log.w(TAG, "WebRTC switchIdentity warning", e);
        }

        // 4. Clear stale persona notifications
        try {
            NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancelAll();
            }
        } catch (Exception ignored) {}

        // 5. Broadcast global persona switch event & notify all registered UI listeners
        Intent broadcast = new Intent(ACTION_PERSONA_SWITCHED);
        broadcast.setPackage(appContext.getPackageName());
        broadcast.putExtra(EXTRA_PERSONA_ID, active.getId());
        broadcast.putExtra(EXTRA_PERSONA_NAME, active.getDisplayName());
        broadcast.putExtra(EXTRA_PERSONA_USERNAME, active.getUsername());
        broadcast.putExtra(EXTRA_PERSONA_KEY_PAIR_ID, getActivePersonaPeerId());
        appContext.sendBroadcast(broadcast);

        notifyPersonaListeners(active);
        return true;
    }

    private void notifyPersonaListeners(Persona activePersona) {
        mainHandler.post(() -> {
            for (PersonaSwitchListener listener : listeners) {
                try {
                    listener.onPersonaSwitched(activePersona);
                } catch (Exception e) {
                    Log.e(TAG, "Listener error in onPersonaSwitched", e);
                }
            }
        });
    }
}
