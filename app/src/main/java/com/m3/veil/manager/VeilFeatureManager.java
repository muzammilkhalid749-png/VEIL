package com.m3.veil.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
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

    private void ensureDefaultPersonas() {
        String json = prefs.getString("personas_json", null);
        if (json == null || json.isEmpty()) {
            try {
                JSONArray arr = new JSONArray();

                JSONObject p1 = new JSONObject();
                p1.put("id", "persona_primary");
                p1.put("name", "@ShadowVeil");
                p1.put("bio", "Primary zero-trace identity");
                p1.put("mood", "🛡️ Encrypted");
                p1.put("bubbleColor", "#0B57CF");
                p1.put("temporary", false);
                arr.put(p1);

                JSONObject p2 = new JSONObject();
                p2.put("id", "persona_ghost");
                p2.put("name", "@MidnightDrifter");
                p2.put("bio", "Ephemeral night owl persona");
                p2.put("mood", "🌙 Incognito");
                p2.put("bubbleColor", "#6A1B9A");
                p2.put("temporary", true);
                arr.put(p2);

                JSONObject p3 = new JSONObject();
                p3.put("id", "persona_neon");
                p3.put("name", "@CyberPulse");
                p3.put("bio", "VeilBoard creator & debater");
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
        try {
            JSONArray arr = new JSONArray(prefs.getString("personas_json", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Persona p = new Persona(
                        o.optString("id"),
                        o.optString("name"),
                        o.optString("bio"),
                        o.optString("bubbleColor", "#0B57CF"),
                        o.optString("mood", "✨ Active"),
                        o.optBoolean("temporary", false),
                        o.optLong("expiresAt", 0L)
                );
                list.add(p);
            }
        } catch (Exception ignored) {}
        return list;
    }

    public Persona getActivePersona() {
        String activeId = prefs.getString("active_persona_id", "persona_primary");
        List<Persona> all = getPersonas();
        for (Persona p : all) {
            if (p.getPersonaId().equals(activeId)) return p;
        }
        return all.isEmpty() ? new Persona("default", "@Anonymous", "Encrypted user", "#0B57CF", "✨ Active", false) : all.get(0);
    }

    public void setActivePersona(String personaId) {
        prefs.edit().putString("active_persona_id", personaId).putBoolean("decoy_mode_active", false).apply();
        com.m3.veil.database.EncryptedChatDatabase.switchActiveVault(appContext, personaId, false);
        try {
            VeilMessagesController.getInstance(appContext).switchActivePersonaVault();
            VeilBoardController.getInstance(appContext).switchActivePersonaVault();
            StatusController.getInstance(appContext).reloadForActiveVault();
        } catch (Throwable ignored) {}
        addXp(5);
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
    }

    public boolean isDecoyVaultActive() {
        return prefs.getBoolean("decoy_mode_active", false);
    }

    public void setActivePersonaId(String personaId) {
        setActivePersona(personaId);
    }

    public void updateActivePersonaDetails(String displayName, String bio) {
        String activeId = prefs.getString("active_persona_id", "persona_primary");
        try {
            JSONArray arr = new JSONArray(prefs.getString("personas_json", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (activeId.equals(o.optString("id"))) {
                    if (displayName != null && !displayName.trim().isEmpty()) {
                        String clean = displayName.startsWith("@") ? displayName.trim() : "@" + displayName.trim();
                        o.put("name", clean);
                    }
                    if (bio != null) o.put("bio", bio.trim());
                    break;
                }
            }
            prefs.edit().putString("personas_json", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public Persona addPersona(String displayName, String bio, String mood, String bubbleColorHex) {
        return addPersona(displayName, bio, mood, bubbleColorHex, false);
    }

    public Persona addPersona(String displayName, String bio, String mood, String bubbleColorHex, boolean isTemporary) {
        return addPersona(displayName, bio, mood, bubbleColorHex, isTemporary, isTemporary ? 86400000L : 0L);
    }

    public Persona addPersona(String displayName, String bio, String mood, String bubbleColorHex, boolean isTemporary, long durationMillis) {
        try {
            JSONArray arr = new JSONArray(prefs.getString("personas_json", "[]"));
            String id = "persona_" + UUID.randomUUID().toString().substring(0, 6);
            String cleanName = displayName.startsWith("@") ? displayName : "@" + displayName;
            long expiresAt = (isTemporary && durationMillis > 0) ? (System.currentTimeMillis() + durationMillis) : 0L;

            JSONObject p = new JSONObject();
            p.put("id", id);
            p.put("name", cleanName);
            p.put("bio", bio);
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
            return new Persona(id, cleanName, bio, bubbleColorHex, mood, isTemporary, expiresAt);
        } catch (Exception e) {
            return getActivePersona();
        }
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

    public List<StickerPack> getAllStickerPacks() {
        List<StickerPack> packs = new ArrayList<>();
        packs.add(new StickerPack("pack_cypherpunk", "⚡ Cypherpunk Glitch", "Satoshi Nakamoto", 0, false,
                java.util.Arrays.asList("⚡", "🤖", "👾", "🕶️", "🦾", "🕹️", "📡", "🛸", "🔋", "💾", "💻", "📟")));
        packs.add(new StickerPack("pack_privacy_whales", "🐋 Privacy Whales", "CypherGuard", 0, false,
                java.util.Arrays.asList("🐋", "🛡️", "🔐", "🕵️", "🕶️", "👁️‍🗨️", "👻", "🗝️", "🧱", "🌊", "🪙", "💎")));
        packs.add(new StickerPack("pack_mesh_memes", "🌐 Mesh Memes", "P2P Swarm", 50, false,
                java.util.Arrays.asList("🐸", "🚀", "🌙", "🔥", "💎", "🦍", "💯", "🎉", "🍿", "🍕", "🍔", "🍻")));
        packs.add(new StickerPack("pack_phantom_glyphs", "🎭 Phantom Glyphs", "ShadowMesh", 80, false,
                java.util.Arrays.asList("🎭", "✨", "💫", "⭐", "🔮", "🧿", "🖤", "🟣", "🔴", "🟢", "🟡", "⚪")));
        packs.addAll(getCustomStickerPacks());
        return packs;
    }

    public List<StickerPack> getInstalledStickerPacks() {
        List<StickerPack> installed = new ArrayList<>();
        List<StickerPack> all = getAllStickerPacks();
        String installedIds = prefs.getString("installed_sticker_packs", "pack_cypherpunk,pack_privacy_whales");
        for (StickerPack p : all) {
            if (installedIds.contains(p.id)) {
                installed.add(p);
            }
        }
        return installed;
    }

    public boolean installStickerPack(String packId) {
        String installedIds = prefs.getString("installed_sticker_packs", "pack_cypherpunk,pack_privacy_whales");
        if (!installedIds.contains(packId)) {
            prefs.edit().putString("installed_sticker_packs", installedIds + "," + packId).apply();
        }
        return true;
    }

    public boolean uninstallStickerPack(String packId) {
        String installedIds = prefs.getString("installed_sticker_packs", "pack_cypherpunk,pack_privacy_whales");
        List<String> list = new ArrayList<>();
        for (String id : installedIds.split(",")) {
            if (!id.trim().equals(packId) && !id.trim().isEmpty()) {
                list.add(id.trim());
            }
        }
        prefs.edit().putString("installed_sticker_packs", TextUtils.join(",", list)).apply();
        return true;
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
        boolean pinConfigured = mainPrefs.getString("app_pin", null) != null;
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

    public void setMasterPin(String pin) {
        if (pin == null || pin.trim().isEmpty()) {
            mainPrefs.edit().remove("app_pin").apply();
        } else {
            mainPrefs.edit().putString("app_pin", pin.trim()).apply();
        }
    }

    public boolean verifyMasterPin(String input) {
        String saved = getMasterPin();
        return saved != null && saved.equals(input != null ? input.trim() : "");
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
        String myUid = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(appContext);
        if (myUid == null || myUid.isEmpty()) myUid = "me";
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
