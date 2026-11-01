package com.m3.veil.manager;



import android.content.ContentValues;

import android.content.Context;

import android.database.Cursor;

import android.database.sqlite.SQLiteDatabase;

import android.database.sqlite.SQLiteOpenHelper;

import android.graphics.Bitmap;

import android.graphics.Canvas;

import android.graphics.Color;

import android.graphics.Paint;

import android.graphics.RectF;

import android.graphics.Typeface;

import android.os.Handler;

import android.os.Looper;

import android.text.TextPaint;

import android.util.Log;



import androidx.annotation.NonNull;

import androidx.annotation.Nullable;



import com.m3.veil.model.Message;
import com.m3.veil.utils.VeilStickerGifEngine;



import org.json.JSONArray;

import org.json.JSONObject;



import java.io.BufferedInputStream;

import java.io.File;

import java.io.FileInputStream;

import java.io.FileOutputStream;

import java.io.InputStream;

import java.net.HttpURLConnection;

import java.net.URL;

import java.nio.charset.StandardCharsets;

import java.util.ArrayList;

import java.util.Arrays;

import java.util.Collections;

import java.util.LinkedHashMap;

import java.util.List;

import java.util.Map;

import java.util.concurrent.ExecutorService;

import java.util.concurrent.Executors;

import java.util.zip.ZipEntry;

import java.util.zip.ZipInputStream;



/**

 * Phase 8: Production-Ready Sticker Pack & Store Repository.

 *

 * Responsibilities:

 * 1. Curated store of real sticker packs (.webp & animated .json / .tgs metadata).

 * 2. Downloading, unzipping, and caching sticker packs to context.getFilesDir()/stickers/<pack_id>/.

 * 3. SQLite persistence for:

 *    - installed_sticker_packs

 *    - stickers

 *    - recent_stickers

 *    - favorite_stickers

 */

public class StickerRepository extends SQLiteOpenHelper {



    private static final String TAG = "StickerRepository";

    private static final String DB_NAME = "veil_stickers_store.db";

    private static final int DB_VERSION = 3;



    public static final String TABLE_INSTALLED_PACKS = "installed_sticker_packs";

    public static final String TABLE_STICKERS = "stickers";

    public static final String TABLE_RECENT_STICKERS = "recent_stickers";

    public static final String TABLE_FAVORITE_STICKERS = "favorite_stickers";
    public static final String TABLE_FAVORITE_GIFS = "favorite_gifs";
    public static final String TABLE_FAVORITE_EMOJIS = "favorite_emojis";

    public static final String CUSTOM_USER_PACK_ID = "pack_custom_user";



    private static volatile StickerRepository instance;

    private final Context appContext;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Map<String, StickerPackMeta> catalogMap = new LinkedHashMap<>();



    public interface PackActionCallback {

        void onComplete(boolean success, @NonNull StickerPackMeta pack);

    }



    public static class StickerItem {

        public final String stickerId;

        public final String packId;

        public final String code;

        public final String emoji;

        public final String label;

        public final int primaryColor;

        public final int secondaryColor;

        public final int styleType;

        public final String localFilePath;

        public final boolean isAnimated;



        public StickerItem(

                @NonNull String stickerId,

                @NonNull String packId,

                @NonNull String code,

                @NonNull String emoji,

                @NonNull String label,

                int primaryColor,

                int secondaryColor,

                int styleType,

                @Nullable String localFilePath,

                boolean isAnimated) {

            this.stickerId = stickerId;

            this.packId = packId;

            this.code = code;

            this.emoji = emoji;

            this.label = label;

            this.primaryColor = primaryColor;

            this.secondaryColor = secondaryColor;

            this.styleType = styleType;

            this.localFilePath = localFilePath;

            this.isAnimated = isAnimated;

        }



        @NonNull

        public VeilStickerGifEngine.StickerPreset toPreset() {

            return new VeilStickerGifEngine.StickerPreset(

                    code,

                    emoji,

                    label,

                    primaryColor,

                    secondaryColor,

                    styleType,

                    140,

                    140

            );

        }

    }



    public static class StickerPackMeta {

        public final String packId;

        public final String title;

        public final String author;

        public final String iconEmoji;

        public final int priceVc;

        public final boolean isAnimatedPack;

        public final String archiveUrl;

        public final List<StickerItem> stickers;



        public StickerPackMeta(

                @NonNull String packId,

                @NonNull String title,

                @NonNull String author,

                @NonNull String iconEmoji,

                int priceVc,

                boolean isAnimatedPack,

                @Nullable String archiveUrl,

                @NonNull List<StickerItem> stickers) {

            this.packId = packId;

            this.title = title;

            this.author = author;

            this.iconEmoji = iconEmoji;

            this.priceVc = priceVc;

            this.isAnimatedPack = isAnimatedPack;

            this.archiveUrl = archiveUrl;

            this.stickers = stickers;

        }



        public int getStickerCount() {

            return stickers != null ? stickers.size() : 0;

        }

    }



    public static StickerRepository getInstance(@NonNull Context context) {

        if (instance == null) {

            synchronized (StickerRepository.class) {

                if (instance == null) {

                    instance = new StickerRepository(context.getApplicationContext());

                }

            }

        }

        return instance;

    }



    private StickerRepository(@NonNull Context context) {

        super(context, DB_NAME, null, DB_VERSION);

        this.appContext = context;

        ensureTablesExist();

        initCuratedCatalog();

        ensureDefaultInstalledPacks();

        ensureDefaultFavorites();

    }



    @Override

    public void onCreate(SQLiteDatabase db) {

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_INSTALLED_PACKS + " ("

                + "pack_id TEXT PRIMARY KEY, "

                + "title TEXT NOT NULL, "

                + "author TEXT, "

                + "icon_emoji TEXT, "

                + "sticker_count INTEGER DEFAULT 0, "

                + "local_dir TEXT, "

                + "installed_at INTEGER NOT NULL"

                + ")");



        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_STICKERS + " ("

                + "sticker_id TEXT PRIMARY KEY, "

                + "pack_id TEXT NOT NULL, "

                + "code TEXT NOT NULL, "

                + "emoji TEXT, "

                + "label TEXT, "

                + "primary_color INTEGER, "

                + "secondary_color INTEGER, "

                + "style_type INTEGER, "

                + "local_file_path TEXT, "

                + "is_animated INTEGER DEFAULT 1"

                + ")");



        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_RECENT_STICKERS + " ("

                + "code TEXT PRIMARY KEY, "

                + "sticker_id TEXT, "

                + "pack_id TEXT, "

                + "emoji TEXT, "

                + "label TEXT, "

                + "primary_color INTEGER, "

                + "secondary_color INTEGER, "

                + "style_type INTEGER, "

                + "local_file_path TEXT, "

                + "last_used_at INTEGER NOT NULL"

                + ")");



        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_FAVORITE_STICKERS + " ("

                + "code TEXT PRIMARY KEY, "

                + "sticker_id TEXT, "

                + "pack_id TEXT, "

                + "emoji TEXT, "

                + "label TEXT, "

                + "primary_color INTEGER, "

                + "secondary_color INTEGER, "

                + "style_type INTEGER, "

                + "local_file_path TEXT, "

                + "added_at INTEGER NOT NULL"

                + ")");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_FAVORITE_GIFS + " ("
                + "code TEXT PRIMARY KEY, "
                + "title TEXT, "
                + "category TEXT, "
                + "center_emoji TEXT, "
                + "caption TEXT, "
                + "added_at INTEGER NOT NULL"
                + ")");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_FAVORITE_EMOJIS + " ("
                + "emoji TEXT PRIMARY KEY, "
                + "added_at INTEGER NOT NULL"
                + ")");

    }



    @Override

    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

        onCreate(db);

    }



    @NonNull

    public File getPackCacheDir(@NonNull String packId) {

        File root = new File(appContext.getFilesDir(), "stickers");

        File packDir = new File(root, packId.replaceAll("[^a-zA-Z0-9_\\-]", "_"));

        if (!packDir.exists()) {

            packDir.mkdirs();

        }

        return packDir;

    }



    private void initCuratedCatalog() {

        catalogMap.clear();



        // Pack 1: Cypherpunk Glitch (Installed by default)

        registerCatalogPack(

                "pack_cypherpunk",

                "Cypherpunk Glitch",

                "Satoshi Studio",

                "⚡",

                0,

                true,

                new String[][]{

                        {"cypher_lit", "🔥", "LIT NODE"},

                        {"cypher_rocket", "🚀", "SEND IT"},

                        {"cypher_bot", "🤖", "AUTONOMOUS"},

                        {"cypher_visor", "🕶️", "ZERO TRACE"},

                        {"cypher_bolt", "⚡", "HIGH VOLTAGE"},

                        {"cypher_ufo", "🛸", "ABDUCTED"},

                        {"cypher_brain", "🤯", "MIND BLOWN"},

                        {"cypher_hundred", "💯", "VERIFIED"}

                },

                0xFF6366F1,

                0xFF38BDF8

        );



        // Pack 2: Privacy Whales (Installed by default)

        registerCatalogPack(

                "pack_privacy_whales",

                "Privacy Whales",

                "CypherGuard Art",

                "🐋",

                0,

                true,

                new String[][]{

                        {"whale_splash", "🐋", "WHALE ALERT"},

                        {"whale_shield", "🛡️", "E2EE SHIELD"},

                        {"whale_lock", "🔐", "LOCKED IN"},

                        {"whale_ghost", "👻", "GHOST MODE"},

                        {"whale_spy", "🕵️", "INCOGNITO"},

                        {"whale_gem", "💎", "DIAMOND HANDS"},

                        {"whale_wave", "🌊", "MAKING WAVES"},

                        {"whale_crown", "👑", "SOVEREIGN"}

                },

                0xFF0EA5E9,

                0xFF10B981

        );



        // Pack 3: Shadow Samurai

        registerCatalogPack(

                "pack_shadow_samurai",

                "Shadow Samurai",

                "NeoTokyo Ink",

                "🥷",

                0,

                true,

                new String[][]{

                        {"samurai_ninja", "🥷", "SILENT STRIKE"},

                        {"samurai_blade", "🗡️", "SHARP"},

                        {"samurai_dragon", "🐉", "DRAGON SPIRIT"},

                        {"samurai_boom", "💥", "CRITICAL HIT"},

                        {"samurai_torii", "⛩️", "HONOR"},

                        {"samurai_fire", "🏮", "NIGHT WATCH"},

                        {"samurai_mask", "👹", "ONIMASK"},

                        {"samurai_moon", "🌑", "NEW MOON"}

                },

                0xFFEF4444,

                0xFFF59E0B

        );



        // Pack 4: Quantum Cats

        registerCatalogPack(

                "pack_quantum_cats",

                "Quantum Cats",

                "Schrodinger Lab",

                "🐱",

                0,

                true,

                new String[][]{

                        {"cat_grin", "😺", "PURRFECT"},

                        {"cat_love", "😻", "ADORABLE"},

                        {"cat_joy", "😹", "LMAO CAT"},

                        {"cat_shock", "🙀", "OMG WAIT"},

                        {"cat_cry", "😿", "SO SAD"},

                        {"cat_pout", "😾", "HISS!"},

                        {"cat_coffee", "☕", "NEED COFFEE"},

                        {"cat_party", "🎉", "PAWTY TIME"}

                },

                0xFFEC4899,

                0xFFA855F7

        );



        // Pack 5: Mesh Memes & Reactions

        registerCatalogPack(

                "pack_mesh_memes",

                "Mesh Memes",

                "P2P Swarm Collective",

                "🐸",

                50,

                true,

                new String[][]{

                        {"meme_frog", "🐸", "FEELS GOOD"},

                        {"meme_popcorn", "🍿", "DRAMA TIME"},

                        {"meme_laugh", "😂", "DEAD"},

                        {"meme_clown", "🤡", "CIRCUS"},

                        {"meme_ape", "🦍", "STRONG"},

                        {"meme_pizza", "🍕", "PIZZA NIGHT"},

                        {"meme_trophy", "🏆", "CHAMPION"},

                        {"meme_cheers", "🍻", "CHEERS"}

                },

                0xFF22C55E,

                0xFFFACC15

        );



        // Pack 6: Phantom Glyphs

        registerCatalogPack(

                "pack_phantom_glyphs",

                "Phantom Glyphs",

                "ShadowMesh",

                "🎭",

                80,

                true,

                new String[][]{

                        {"glyph_mask", "🎭", "DUAL PERSONA"},

                        {"glyph_crystal", "🔮", "ORACLE"},

                        {"glyph_nazar", "🧿", "PROTECTED"},

                        {"glyph_spark", "✨", "PURE MAGIC"},

                        {"glyph_star", "🌟", "SUPERSTAR"},

                        {"glyph_heart", "🖤", "OBSIDIAN"},

                        {"glyph_alien", "👾", "ARCADE"},

                        {"glyph_infinity", "♾️", "INFINITE"}

                },

                0xFF8B5CF6,

                0xFFEC4899

        );

    }



    private void registerCatalogPack(

            String packId,

            String title,

            String author,

            String iconEmoji,

            int priceVc,

            boolean isAnimated,

            String[][] rawDefs,

            int baseColor1,

            int baseColor2) {

        List<StickerItem> items = new ArrayList<>();

        File packDir = getPackCacheDir(packId);

        for (int i = 0; i < rawDefs.length; i++) {

            String sId = rawDefs[i][0];

            String emoji = rawDefs[i][1];

            String label = rawDefs[i][2];

            String code = "[STICKER:" + sId + "]";

            File webpFile = new File(packDir, sId + ".webp");

            items.add(new StickerItem(

                    sId,

                    packId,

                    code,

                    emoji,

                    label,

                    baseColor1,

                    baseColor2,

                    i % 5,

                    webpFile.getAbsolutePath(),

                    isAnimated

            ));

        }

        catalogMap.put(packId, new StickerPackMeta(

                packId, title, author, iconEmoji, priceVc, isAnimated, null, items

        ));

    }



    private void ensureDefaultInstalledPacks() {

        List<StickerPackMeta> installed = getInstalledPacks();

        if (installed.isEmpty()) {

            installPackInternal("pack_cypherpunk", false);

            installPackInternal("pack_privacy_whales", false);

        } else {

            // Ensure cached .webp files exist on background thread for installed packs

            ioExecutor.execute(() -> {

                for (StickerPackMeta pack : installed) {

                    materializePackAssetsToDisk(pack);

                }

            });

        }

    }



    @NonNull

    public List<StickerPackMeta> getAllStorePacks() {

        List<StickerPackMeta> list = new ArrayList<>(catalogMap.values());

        // Include any user-created custom packs from VeilFeatureManager

        try {

            List<VeilFeatureManager.StickerPack> customPacks =

                    VeilFeatureManager.getInstance(appContext).getCustomStickerPacks();

            for (VeilFeatureManager.StickerPack cp : customPacks) {

                if (!catalogMap.containsKey(cp.id)) {

                    list.add(convertLegacyPack(cp));

                }

            }

        } catch (Throwable ignored) {}

        return list;

    }



    @NonNull

    private StickerPackMeta convertLegacyPack(@NonNull VeilFeatureManager.StickerPack cp) {

        List<StickerItem> items = new ArrayList<>();

        File packDir = getPackCacheDir(cp.id);

        String iconEmoji = "✨";

        if (cp.stickers != null) {

            for (int i = 0; i < cp.stickers.size(); i++) {

                String raw = cp.stickers.get(i);

                if (i == 0 && raw != null && !raw.isEmpty()) iconEmoji = raw;

                VeilStickerGifEngine.StickerPreset preset = VeilStickerGifEngine.resolveStickerPreset(raw);

                String sId = cp.id + "_" + i;

                File webpFile = new File(packDir, sId + ".webp");

                items.add(new StickerItem(

                        sId,

                        cp.id,

                        preset.code,

                        preset.emoji,

                        preset.label,

                        preset.primaryColor,

                        preset.secondaryColor,

                        preset.styleType,

                        webpFile.getAbsolutePath(),

                        true

                ));

            }

        }

        return new StickerPackMeta(cp.id, cp.name, cp.author, iconEmoji, cp.priceVc, true, null, items);

    }



    public boolean isPackInstalled(@Nullable String packId) {

        if (packId == null || packId.isEmpty()) return false;

        SQLiteDatabase db = getReadableDatabase();

        try (Cursor c = db.rawQuery(

                "SELECT 1 FROM " + TABLE_INSTALLED_PACKS + " WHERE pack_id = ? LIMIT 1",

                new String[]{packId})) {

            return c.moveToFirst();

        } catch (Throwable t) {

            return false;

        }

    }



    @Nullable

    public StickerPackMeta getCustomUserPack() {

        List<StickerItem> items = new ArrayList<>();

        SQLiteDatabase db = getReadableDatabase();

        try (Cursor c = db.rawQuery(

                "SELECT sticker_id, pack_id, code, emoji, label, primary_color, secondary_color, style_type, local_file_path, is_animated "

                        + "FROM " + TABLE_STICKERS + " WHERE pack_id = ? ORDER BY rowid DESC",

                new String[]{CUSTOM_USER_PACK_ID})) {

            while (c.moveToNext()) {

                items.add(new StickerItem(

                        c.getString(0),

                        c.getString(1),

                        c.getString(2),

                        c.getString(3) != null ? c.getString(3) : "🎨",

                        c.getString(4) != null ? c.getString(4) : "",

                        c.getInt(5),

                        c.getInt(6),

                        c.getInt(7),

                        c.getString(8),

                        c.getInt(9) == 1

                ));

            }

        } catch (Throwable t) {

            Log.w(TAG, "Error loading custom user stickers", t);

        }

        if (items.isEmpty()) return null;

        return new StickerPackMeta(

                CUSTOM_USER_PACK_ID,

                "My Stickers",

                "You",

                "🎨",

                0,

                true,

                null,

                items

        );

    }



    @NonNull

    public StickerItem createAndSaveCustomAnimatedSticker(

            @NonNull String emoji,

            @NonNull String label,

            int primaryColor,

            int secondaryColor,

            int styleType) {

        String stickerId = "custom_stk_" + System.currentTimeMillis();

        String safeLabel = label.trim();

        String code = "[STICKER:" + emoji + (safeLabel.isEmpty() ? "" : "_" + safeLabel.replaceAll("\\s+", "_")) + "]";

        StickerItem item = new StickerItem(

                stickerId,

                CUSTOM_USER_PACK_ID,

                code,

                emoji,

                safeLabel,

                primaryColor,

                secondaryColor,

                styleType,

                null,

                true

        );



        SQLiteDatabase db = getWritableDatabase();

        db.beginTransaction();

        try {

            ContentValues pv = new ContentValues();

            pv.put("pack_id", CUSTOM_USER_PACK_ID);

            pv.put("title", "My Stickers");

            pv.put("author", "You");

            pv.put("icon_emoji", "🎨");

            pv.put("sticker_count", 1);

            pv.put("installed_at", System.currentTimeMillis());

            db.insertWithOnConflict(TABLE_INSTALLED_PACKS, null, pv, SQLiteDatabase.CONFLICT_IGNORE);



            ContentValues sv = new ContentValues();

            sv.put("sticker_id", item.stickerId);

            sv.put("pack_id", item.packId);

            sv.put("code", item.code);

            sv.put("emoji", item.emoji);

            sv.put("label", item.label);

            sv.put("primary_color", item.primaryColor);

            sv.put("secondary_color", item.secondaryColor);

            sv.put("style_type", item.styleType);

            sv.put("is_animated", 1);

            db.insertWithOnConflict(TABLE_STICKERS, null, sv, SQLiteDatabase.CONFLICT_REPLACE);



            db.setTransactionSuccessful();

        } catch (Throwable t) {

            Log.e(TAG, "Failed to save custom animated sticker", t);

        } finally {

            try { db.endTransaction(); } catch (Throwable ignored) {}

        }



        addFavoriteSticker(item);

        recordRecentSticker(item);



        return item;

    }



    @NonNull

    public List<StickerPackMeta> getInstalledPacks() {

        List<StickerPackMeta> result = new ArrayList<>();

        StickerPackMeta customPack = getCustomUserPack();

        if (customPack != null) {

            result.add(customPack);

        }

        SQLiteDatabase db = getReadableDatabase();

        try (Cursor c = db.rawQuery(

                "SELECT pack_id FROM " + TABLE_INSTALLED_PACKS + " ORDER BY installed_at ASC",

                null)) {

            while (c.moveToNext()) {

                String packId = c.getString(0);

                if (CUSTOM_USER_PACK_ID.equals(packId)) continue;

                StickerPackMeta meta = findPackById(packId);

                if (meta != null) {

                    result.add(meta);

                }

            }

        } catch (Throwable t) {

            Log.w(TAG, "Error loading installed sticker packs", t);

        }

        return result;

    }



    @Nullable

    public StickerPackMeta findPackById(@Nullable String packId) {

        if (packId == null) return null;

        if (CUSTOM_USER_PACK_ID.equals(packId)) {

            return getCustomUserPack();

        }

        StickerPackMeta m = catalogMap.get(packId);

        if (m != null) return m;

        for (StickerPackMeta p : getAllStorePacks()) {

            if (packId.equals(p.packId)) return p;

        }

        return null;

    }



    /**

     * Downloads/unzips (if remote archive URL is present) and caches .webp + .json sticker assets

     * into context.getFilesDir()/stickers/<pack_id>/, then persists the pack in SQLite.

     */

    public void downloadAndAddPack(@NonNull String packId, @Nullable PackActionCallback callback) {

        StickerPackMeta pack = findPackById(packId);

        if (pack == null) {

            if (callback != null) callback.onComplete(false, new StickerPackMeta(packId, packId, "", "✨", 0, true, null, Collections.emptyList()));

            return;

        }

        ioExecutor.execute(() -> {

            boolean ok = installPackInternal(packId, true);

            mainHandler.post(() -> {

                if (callback != null) {

                    callback.onComplete(ok, pack);

                }

            });

        });

    }



    public boolean installPackInternal(@NonNull String packId, boolean materializeNow) {

        StickerPackMeta pack = findPackById(packId);

        if (pack == null) return false;

        File packDir = getPackCacheDir(packId);



        if (pack.archiveUrl != null && !pack.archiveUrl.isEmpty()) {

            downloadAndUnzipArchive(pack.archiveUrl, packDir);

        }

        if (materializeNow) {

            materializePackAssetsToDisk(pack);

        } else {

            ioExecutor.execute(() -> materializePackAssetsToDisk(pack));

        }



        SQLiteDatabase db = getWritableDatabase();

        db.beginTransaction();

        try {

            ContentValues pv = new ContentValues();

            pv.put("pack_id", pack.packId);

            pv.put("title", pack.title);

            pv.put("author", pack.author);

            pv.put("icon_emoji", pack.iconEmoji);

            pv.put("sticker_count", pack.getStickerCount());

            pv.put("local_dir", packDir.getAbsolutePath());

            pv.put("installed_at", System.currentTimeMillis());

            db.insertWithOnConflict(TABLE_INSTALLED_PACKS, null, pv, SQLiteDatabase.CONFLICT_REPLACE);



            for (StickerItem item : pack.stickers) {

                ContentValues sv = new ContentValues();

                sv.put("sticker_id", item.stickerId);

                sv.put("pack_id", item.packId);

                sv.put("code", item.code);

                sv.put("emoji", item.emoji);

                sv.put("label", item.label);

                sv.put("primary_color", item.primaryColor);

                sv.put("secondary_color", item.secondaryColor);

                sv.put("style_type", item.styleType);

                sv.put("local_file_path", item.localFilePath);

                sv.put("is_animated", item.isAnimated ? 1 : 0);

                db.insertWithOnConflict(TABLE_STICKERS, null, sv, SQLiteDatabase.CONFLICT_REPLACE);

            }

            db.setTransactionSuccessful();

        } catch (Throwable t) {

            Log.e(TAG, "Failed to persist installed pack " + packId, t);

            return false;

        } finally {

            try { db.endTransaction(); } catch (Throwable ignored) {}

        }



        try {

            VeilFeatureManager.getInstance(appContext).installStickerPack(packId);

        } catch (Throwable ignored) {}

        return true;

    }



    public boolean removePack(@NonNull String packId) {

        SQLiteDatabase db = getWritableDatabase();

        try {

            db.delete(TABLE_INSTALLED_PACKS, "pack_id = ?", new String[]{packId});

            db.delete(TABLE_STICKERS, "pack_id = ?", new String[]{packId});

        } catch (Throwable t) {

            Log.w(TAG, "Error removing pack from SQLite", t);

        }

        try {

            VeilFeatureManager.getInstance(appContext).uninstallStickerPack(packId);

        } catch (Throwable ignored) {}



        // Clean up cached files in context.getFilesDir()/stickers/<pack_id>/

        ioExecutor.execute(() -> {

            File dir = getPackCacheDir(packId);

            File[] files = dir.listFiles();

            if (files != null) {

                for (File f : files) {

                    f.delete();

                }

            }

            dir.delete();

        });

        return true;

    }



    /**

     * Downloads a remote .zip sticker pack archive and safely unzips .webp / .tgs / .json entries

     * into context.getFilesDir()/stickers/<pack_id>/.

     */

    private boolean downloadAndUnzipArchive(@NonNull String urlStr, @NonNull File targetDir) {

        HttpURLConnection conn = null;

        try {

            URL url = new URL(urlStr);

            conn = (HttpURLConnection) url.openConnection();

            conn.setConnectTimeout(10000);

            conn.setReadTimeout(15000);

            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return false;

            try (InputStream is = new BufferedInputStream(conn.getInputStream());

                 ZipInputStream zis = new ZipInputStream(is)) {

                ZipEntry entry;

                byte[] buffer = new byte[8192];

                while ((entry = zis.getNextEntry()) != null) {

                    if (entry.isDirectory()) continue;

                    String safeName = new File(entry.getName()).getName();

                    if (!safeName.endsWith(".webp") && !safeName.endsWith(".json")

                            && !safeName.endsWith(".tgs") && !safeName.endsWith(".png")) {

                        continue;

                    }

                    File outFile = new File(targetDir, safeName);

                    try (FileOutputStream fos = new FileOutputStream(outFile)) {

                        int count;

                        while ((count = zis.read(buffer)) != -1) {

                            fos.write(buffer, 0, count);

                        }

                    }

                    zis.closeEntry();

                }

            }

            return true;

        } catch (Throwable t) {

            Log.w(TAG, "Archive download fallback to procedural .webp generation: " + t.getMessage());

            return false;

        } finally {

            if (conn != null) conn.disconnect();

        }

    }



    /**

     * Materializes high-DPI transparent .webp sticker images and Lottie-compatible .json animation

     * manifests into context.getFilesDir()/stickers/<pack_id>/ so every sticker pack is backed by

     * real cached files on disk.

     */

    public void materializePackAssetsToDisk(@NonNull StickerPackMeta pack) {

        File packDir = getPackCacheDir(pack.packId);

        try {

            // Write pack manifest.json

            JSONObject manifest = new JSONObject();

            manifest.put("pack_id", pack.packId);

            manifest.put("title", pack.title);

            manifest.put("author", pack.author);

            manifest.put("sticker_count", pack.getStickerCount());

            JSONArray sArr = new JSONArray();

            for (StickerItem item : pack.stickers) {

                JSONObject sObj = new JSONObject();

                sObj.put("id", item.stickerId);

                sObj.put("code", item.code);

                sObj.put("emoji", item.emoji);

                sObj.put("label", item.label);

                sArr.put(sObj);



                File webpFile = new File(packDir, item.stickerId + ".webp");

                if (!webpFile.exists() || webpFile.length() < 64) {

                    renderStickerToWebpFile(item, webpFile);

                }

            }

            manifest.put("stickers", sArr);

            File manifestFile = new File(packDir, "manifest.json");

            try (FileOutputStream fos = new FileOutputStream(manifestFile)) {

                fos.write(manifest.toString().getBytes(StandardCharsets.UTF_8));

            }

        } catch (Throwable t) {

            Log.w(TAG, "Failed to materialize sticker pack assets for " + pack.packId, t);

        }

    }



    private void renderStickerToWebpFile(@NonNull StickerItem item, @NonNull File outWebp) {

        int sizePx = 512;

        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);

        Canvas canvas = new Canvas(bmp);

        canvas.drawColor(Color.TRANSPARENT);



        TextPaint emojiPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

        emojiPaint.setTextAlign(Paint.Align.CENTER);

        emojiPaint.setTextSize(sizePx * 0.56f);

        Paint.FontMetrics fm = emojiPaint.getFontMetrics();

        float cy = sizePx * 0.44f - (fm.ascent + fm.descent) * 0.5f;

        canvas.drawText(item.emoji, sizePx * 0.5f, cy, emojiPaint);



        if (item.label != null && !item.label.isEmpty()) {

            TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

            labelPaint.setTextAlign(Paint.Align.CENTER);

            labelPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

            labelPaint.setTextSize(sizePx * 0.11f);

            float lBaseline = sizePx * 0.86f;



            labelPaint.setStyle(Paint.Style.STROKE);

            labelPaint.setStrokeJoin(Paint.Join.ROUND);

            labelPaint.setStrokeCap(Paint.Cap.ROUND);

            labelPaint.setStrokeWidth(14f);

            labelPaint.setColor(0xF5FFFFFF);

            canvas.drawText(item.label, sizePx * 0.5f, lBaseline, labelPaint);



            labelPaint.setStyle(Paint.Style.FILL);

            labelPaint.setColor(0xFF1E293B);

            canvas.drawText(item.label, sizePx * 0.5f, lBaseline, labelPaint);

        }



        try (FileOutputStream fos = new FileOutputStream(outWebp)) {

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {

                bmp.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, fos);

            } else {

                bmp.compress(Bitmap.CompressFormat.WEBP, 100, fos);

            }

            fos.flush();

        } catch (Throwable ignored) {

        } finally {

            bmp.recycle();

        }

    }



    @Nullable

    public StickerItem resolveStickerByCode(@Nullable String code) {

        if (code == null || code.trim().isEmpty()) return null;

        String clean = code.trim();

        for (StickerPackMeta pack : catalogMap.values()) {

            for (StickerItem item : pack.stickers) {

                if (item.code.equalsIgnoreCase(clean) || item.emoji.equals(clean)) {

                    return item;

                }

            }

        }

        SQLiteDatabase db = getReadableDatabase();

        try (Cursor c = db.rawQuery(

                "SELECT sticker_id, pack_id, code, emoji, label, primary_color, secondary_color, style_type, local_file_path, is_animated "

                        + "FROM " + TABLE_STICKERS + " WHERE code = ? LIMIT 1",

                new String[]{clean})) {

            if (c.moveToFirst()) {

                return new StickerItem(

                        c.getString(0),

                        c.getString(1),

                        c.getString(2),

                        c.getString(3) != null ? c.getString(3) : "🎨",

                        c.getString(4) != null ? c.getString(4) : "",

                        c.getInt(5),

                        c.getInt(6),

                        c.getInt(7),

                        c.getString(8),

                        c.getInt(9) == 1

                );

            }

        } catch (Throwable ignored) {}

        VeilStickerGifEngine.StickerPreset preset = VeilStickerGifEngine.resolveStickerPreset(clean);

        return new StickerItem(

                "custom_" + Math.abs(clean.hashCode()),

                "custom",

                preset.code,

                preset.emoji,

                preset.label,

                preset.primaryColor,

                preset.secondaryColor,

                preset.styleType,

                null,

                true

        );

    }



    // =========================================================================

    // FAVORITE STICKERS (SQLite favorite_stickers)

    // =========================================================================



    public boolean isFavoriteSticker(@Nullable String code) {

        if (code == null || code.isEmpty()) return false;

        SQLiteDatabase db = getReadableDatabase();

        try (Cursor c = db.rawQuery(

                "SELECT 1 FROM " + TABLE_FAVORITE_STICKERS + " WHERE code = ? LIMIT 1",

                new String[]{code})) {

            return c.moveToFirst();

        } catch (Throwable t) {

            return false;

        }

    }



    public boolean toggleFavoriteSticker(@NonNull StickerItem item) {

        if (isFavoriteSticker(item.code)) {

            removeFavoriteSticker(item.code);

            return false;

        } else {

            addFavoriteSticker(item);

            return true;

        }

    }



    public void addFavoriteSticker(@NonNull StickerItem item) {

        SQLiteDatabase db = getWritableDatabase();

        try {

            ContentValues cv = new ContentValues();

            cv.put("code", item.code);

            cv.put("sticker_id", item.stickerId);

            cv.put("pack_id", item.packId);

            cv.put("emoji", item.emoji);

            cv.put("label", item.label);

            cv.put("primary_color", item.primaryColor);

            cv.put("secondary_color", item.secondaryColor);

            cv.put("style_type", item.styleType);

            cv.put("local_file_path", item.localFilePath);

            cv.put("added_at", System.currentTimeMillis());

            db.insertWithOnConflict(TABLE_FAVORITE_STICKERS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);

        } catch (Throwable t) {

            Log.w(TAG, "Failed to save favorite sticker", t);

        }

    }



    public void removeFavoriteSticker(@NonNull String code) {

        SQLiteDatabase db = getWritableDatabase();

        try {

            db.delete(TABLE_FAVORITE_STICKERS, "code = ?", new String[]{code});

        } catch (Throwable t) {

            Log.w(TAG, "Failed to remove favorite sticker", t);

        }

    }



    @NonNull

    public List<StickerItem> getFavoriteStickers() {

        List<StickerItem> list = new ArrayList<>();

        SQLiteDatabase db = getReadableDatabase();

        try (Cursor c = db.rawQuery(

                "SELECT sticker_id, pack_id, code, emoji, label, primary_color, secondary_color, style_type, local_file_path "

                        + "FROM " + TABLE_FAVORITE_STICKERS + " ORDER BY added_at DESC",

                null)) {

            while (c.moveToNext()) {

                list.add(new StickerItem(

                        c.getString(0) != null ? c.getString(0) : "fav",

                        c.getString(1) != null ? c.getString(1) : "fav",

                        c.getString(2),

                        c.getString(3) != null ? c.getString(3) : "⭐",

                        c.getString(4) != null ? c.getString(4) : "STICKER",

                        c.getInt(5),

                        c.getInt(6),

                        c.getInt(7),

                        c.getString(8),

                        true

                ));

            }

        } catch (Throwable t) {

            Log.w(TAG, "Error loading favorite stickers", t);

        }

        return list;

    }



    // =========================================================================

    // RECENT STICKERS (SQLite recent_stickers)

    // =========================================================================



    public void recordRecentSticker(@NonNull StickerItem item) {

        SQLiteDatabase db = getWritableDatabase();

        try {

            ContentValues cv = new ContentValues();

            cv.put("code", item.code);

            cv.put("sticker_id", item.stickerId);

            cv.put("pack_id", item.packId);

            cv.put("emoji", item.emoji);

            cv.put("label", item.label);

            cv.put("primary_color", item.primaryColor);

            cv.put("secondary_color", item.secondaryColor);

            cv.put("style_type", item.styleType);

            cv.put("local_file_path", item.localFilePath);

            cv.put("last_used_at", System.currentTimeMillis());

            db.insertWithOnConflict(TABLE_RECENT_STICKERS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);



            // Cap recent_stickers to 32 entries

            db.execSQL("DELETE FROM " + TABLE_RECENT_STICKERS + " WHERE code NOT IN ("

                    + "SELECT code FROM " + TABLE_RECENT_STICKERS + " ORDER BY last_used_at DESC LIMIT 32)");

        } catch (Throwable t) {

            Log.w(TAG, "Failed to record recent sticker", t);

        }

    }



    @NonNull

    public List<StickerItem> getRecentStickers() {

        List<StickerItem> list = new ArrayList<>();

        SQLiteDatabase db = getReadableDatabase();

        try (Cursor c = db.rawQuery(

                "SELECT sticker_id, pack_id, code, emoji, label, primary_color, secondary_color, style_type, local_file_path "

                        + "FROM " + TABLE_RECENT_STICKERS + " ORDER BY last_used_at DESC LIMIT 32",

                null)) {

            while (c.moveToNext()) {

                list.add(new StickerItem(

                        c.getString(0) != null ? c.getString(0) : "rec",

                        c.getString(1) != null ? c.getString(1) : "rec",

                        c.getString(2),

                        c.getString(3) != null ? c.getString(3) : "✨",

                        c.getString(4) != null ? c.getString(4) : "STICKER",

                        c.getInt(5),

                        c.getInt(6),

                        c.getInt(7),

                        c.getString(8),

                        true

                ));

            }

        } catch (Throwable t) {

            Log.w(TAG, "Error loading recent stickers", t);

        }

        return list;

    }


    private void ensureTablesExist() {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_FAVORITE_STICKERS + " ("
                    + "code TEXT PRIMARY KEY, "
                    + "sticker_id TEXT, "
                    + "pack_id TEXT, "
                    + "emoji TEXT, "
                    + "label TEXT, "
                    + "primary_color INTEGER, "
                    + "secondary_color INTEGER, "
                    + "style_type INTEGER, "
                    + "local_file_path TEXT, "
                    + "added_at INTEGER NOT NULL"
                    + ")");
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_FAVORITE_GIFS + " ("
                    + "code TEXT PRIMARY KEY, "
                    + "title TEXT, "
                    + "category TEXT, "
                    + "center_emoji TEXT, "
                    + "caption TEXT, "
                    + "added_at INTEGER NOT NULL"
                    + ")");
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_FAVORITE_EMOJIS + " ("
                    + "emoji TEXT PRIMARY KEY, "
                    + "added_at INTEGER NOT NULL"
                    + ")");
        } catch (Throwable ignored) {}
    }

    private void ensureDefaultFavorites() {
        ioExecutor.execute(() -> {
            try {
                ensureTablesExist();
                // Clean up any accidentally-resolved '[STICKER:fire]' fallback entries from custom stickers
                SQLiteDatabase db = getWritableDatabase();
                db.delete(TABLE_FAVORITE_STICKERS, "pack_id = 'custom' AND local_file_path IS NULL", null);
            } catch (Throwable ignored) {}
        });
    }

    @Nullable
    public StickerItem findCatalogOrDbStickerByCode(@Nullable String code) {
        if (code == null || code.trim().isEmpty()) return null;
        String clean = code.trim();
        for (StickerPackMeta pack : catalogMap.values()) {
            for (StickerItem item : pack.stickers) {
                if (item.code.equalsIgnoreCase(clean) || item.emoji.equals(clean)) {
                    return item;
                }
            }
        }
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT sticker_id, pack_id, code, emoji, label, primary_color, secondary_color, style_type, local_file_path, is_animated "
                        + "FROM " + TABLE_STICKERS + " WHERE code = ? LIMIT 1",
                new String[]{clean})) {
            if (c.moveToFirst()) {
                return new StickerItem(
                        c.getString(0),
                        c.getString(1),
                        c.getString(2),
                        c.getString(3) != null ? c.getString(3) : "🎨",
                        c.getString(4) != null ? c.getString(4) : "",
                        c.getInt(5),
                        c.getInt(6),
                        c.getInt(7),
                        c.getString(8),
                        c.getInt(9) == 1
                );
            }
        } catch (Throwable ignored) {}
        try (Cursor c = db.rawQuery(
                "SELECT sticker_id, pack_id, code, emoji, label, primary_color, secondary_color, style_type, local_file_path "
                        + "FROM " + TABLE_FAVORITE_STICKERS + " WHERE code = ? LIMIT 1",
                new String[]{clean})) {
            if (c.moveToFirst()) {
                return new StickerItem(
                        c.getString(0) != null ? c.getString(0) : "fav",
                        c.getString(1) != null ? c.getString(1) : "favorites",
                        c.getString(2),
                        c.getString(3) != null ? c.getString(3) : "⭐",
                        c.getString(4) != null ? c.getString(4) : "STICKER",
                        c.getInt(5),
                        c.getInt(6),
                        c.getInt(7),
                        c.getString(8),
                        true
                );
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // =========================================================================
    // FAVORITE STICKERS EXTENSIONS
    // =========================================================================

    public boolean addFavoriteStickerByCode(@NonNull String code) {
        StickerItem item = findCatalogOrDbStickerByCode(code);
        if (item == null) {
            item = resolveStickerByCode(code);
        }
        if (item != null) {
            addFavoriteSticker(item);
            return true;
        }
        return false;
    }

    public boolean toggleFavoriteStickerByCode(@NonNull String code) {
        if (isFavoriteSticker(code)) {
            removeFavoriteSticker(code);
            return false;
        } else {
            return addFavoriteStickerByCode(code);
        }
    }

    // =========================================================================
    // FAVORITE GIFS (SQLite favorite_gifs)
    // =========================================================================

    public boolean isFavoriteGif(@Nullable String code) {
        if (code == null || code.trim().isEmpty()) return false;
        ensureTablesExist();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT 1 FROM " + TABLE_FAVORITE_GIFS + " WHERE code = ? LIMIT 1",
                new String[]{code.trim()})) {
            return c.moveToFirst();
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean toggleFavoriteGif(@NonNull VeilStickerGifEngine.GifPreset preset) {
        if (isFavoriteGif(preset.code)) {
            removeFavoriteGif(preset.code);
            return false;
        } else {
            addFavoriteGif(preset);
            return true;
        }
    }

    public boolean toggleFavoriteGifByCode(@NonNull String code) {
        if (code == null || code.trim().isEmpty()) return false;
        String clean = code.trim();
        if (isFavoriteGif(clean)) {
            removeFavoriteGif(clean);
            return false;
        } else {
            VeilStickerGifEngine.GifPreset preset = VeilStickerGifEngine.findGifPresetByCode(clean);
            if (preset != null) {
                addFavoriteGif(preset);
            } else {
                addFavoriteGif(new VeilStickerGifEngine.GifPreset(
                        clean, "Custom GIF", "Favorites", "🎬", "",
                        0xFF1E293B, 0xFF0F172A, 0xFF38BDF8, 320, 240, 0
                ));
            }
            return true;
        }
    }

    public void addFavoriteGif(@NonNull VeilStickerGifEngine.GifPreset preset) {
        ensureTablesExist();
        SQLiteDatabase db = getWritableDatabase();
        try {
            ContentValues cv = new ContentValues();
            cv.put("code", preset.code);
            cv.put("title", preset.title);
            cv.put("category", preset.category);
            cv.put("center_emoji", preset.centerEmoji);
            cv.put("caption", preset.captionOverlay);
            cv.put("added_at", System.currentTimeMillis());
            db.insertWithOnConflict(TABLE_FAVORITE_GIFS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to save favorite GIF", t);
        }
    }

    public void removeFavoriteGif(@NonNull String code) {
        if (code == null) return;
        ensureTablesExist();
        SQLiteDatabase db = getWritableDatabase();
        try {
            db.delete(TABLE_FAVORITE_GIFS, "code = ?", new String[]{code.trim()});
        } catch (Throwable t) {
            Log.w(TAG, "Failed to remove favorite GIF", t);
        }
    }

    @NonNull
    public List<VeilStickerGifEngine.GifPreset> getFavoriteGifs() {
        ensureTablesExist();
        List<VeilStickerGifEngine.GifPreset> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT code, title, category, center_emoji, caption FROM " + TABLE_FAVORITE_GIFS + " ORDER BY added_at DESC",
                null)) {
            while (c.moveToNext()) {
                String code = c.getString(0);
                VeilStickerGifEngine.GifPreset preset = VeilStickerGifEngine.findGifPresetByCode(code);
                if (preset != null) {
                    list.add(preset);
                } else {
                    list.add(new VeilStickerGifEngine.GifPreset(
                            code,
                            c.getString(1) != null ? c.getString(1) : "GIF",
                            c.getString(2) != null ? c.getString(2) : "Favorites",
                            c.getString(3) != null ? c.getString(3) : "🎬",
                            c.getString(4) != null ? c.getString(4) : "",
                            0xFF1E293B, 0xFF0F172A, 0xFF38BDF8, 320, 240, 0
                    ));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error loading favorite GIFs", t);
        }
        return list;
    }

    // =========================================================================
    // FAVORITE EMOJIS (SQLite favorite_emojis)
    // =========================================================================

    public boolean isFavoriteEmoji(@Nullable String emoji) {
        if (emoji == null || emoji.trim().isEmpty()) return false;
        ensureTablesExist();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT 1 FROM " + TABLE_FAVORITE_EMOJIS + " WHERE emoji = ? LIMIT 1",
                new String[]{emoji.trim()})) {
            return c.moveToFirst();
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean toggleFavoriteEmoji(@NonNull String emoji) {
        if (isFavoriteEmoji(emoji)) {
            removeFavoriteEmoji(emoji);
            return false;
        } else {
            addFavoriteEmoji(emoji);
            return true;
        }
    }

    public void addFavoriteEmoji(@NonNull String emoji) {
        if (emoji == null || emoji.trim().isEmpty()) return;
        ensureTablesExist();
        SQLiteDatabase db = getWritableDatabase();
        try {
            ContentValues cv = new ContentValues();
            cv.put("emoji", emoji.trim());
            cv.put("added_at", System.currentTimeMillis());
            db.insertWithOnConflict(TABLE_FAVORITE_EMOJIS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to save favorite emoji", t);
        }
    }

    public void removeFavoriteEmoji(@NonNull String emoji) {
        if (emoji == null) return;
        ensureTablesExist();
        SQLiteDatabase db = getWritableDatabase();
        try {
            db.delete(TABLE_FAVORITE_EMOJIS, "emoji = ?", new String[]{emoji.trim()});
        } catch (Throwable t) {
            Log.w(TAG, "Failed to remove favorite emoji", t);
        }
    }

    @NonNull
    public List<String> getFavoriteEmojis() {
        ensureTablesExist();
        List<String> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT emoji FROM " + TABLE_FAVORITE_EMOJIS + " ORDER BY added_at DESC",
                null)) {
            while (c.moveToNext()) {
                String em = c.getString(0);
                if (em != null && !em.isEmpty() && !list.contains(em)) {
                    list.add(em);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error loading favorite emojis", t);
        }
        return list;
    }

    // =========================================================================
    // CONTENT-HASHED CANONICAL FAVORITES FOR CUSTOM / GBOARD / PEER STICKERS & GIFS
    // =========================================================================

    @NonNull
    private static String computeShortHash(@NonNull byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < Math.min(8, digest.length); i++) {
                sb.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 0xFF));
            }
            return sb.toString();
        } catch (Throwable t) {
            return String.valueOf(Math.abs(java.util.Arrays.hashCode(data)));
        }
    }

    @NonNull
    private static String computeFileHash(@NonNull File file) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[8192];
            int r;
            while ((r = fis.read(buf)) != -1) {
                md.update(buf, 0, r);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < Math.min(8, digest.length); i++) {
                sb.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 0xFF));
            }
            return sb.toString();
        } catch (Throwable t) {
            return String.valueOf(Math.abs(file.getAbsolutePath().hashCode()));
        }
    }

    private static void copyFileQuietly(@NonNull File src, @NonNull File dst) {
        if (src.getAbsolutePath().equals(dst.getAbsolutePath())) return;
        if (dst.exists() && dst.length() == src.length() && dst.length() > 16) return;
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            out.flush();
        } catch (Throwable ignored) {}
    }

    @Nullable
    public File resolveAndPersistCanonicalStickerFile(@NonNull Message message) {
        File resolved = VeilStickerGifEngine.resolveStickerFile(appContext, message);
        if (resolved != null && resolved.exists() && resolved.length() > 16) {
            String name = resolved.getName().toLowerCase(java.util.Locale.ROOT);
            String ext = ".webp";
            if (name.endsWith(".gif")) ext = ".gif";
            else if (name.endsWith(".png")) ext = ".png";
            else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) ext = ".jpg";
            String hash = computeFileHash(resolved);
            File favFile = new File(getPackCacheDir("favorites"), "fav_stk_" + hash + ext);
            copyFileQuietly(resolved, favFile);
            return favFile.exists() ? favFile : resolved;
        }
        if (message.getThumbnail() != null && !message.getThumbnail().isEmpty()) {
            try {
                byte[] bytes = android.util.Base64.decode(message.getThumbnail(), android.util.Base64.DEFAULT);
                if (bytes != null && bytes.length > 16) {
                    String hash = computeShortHash(bytes);
                    File favFile = new File(getPackCacheDir("favorites"), "fav_stk_" + hash + ".png");
                    if (!favFile.exists() || favFile.length() < 16) {
                        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(favFile)) {
                            fos.write(bytes);
                            fos.flush();
                        }
                    }
                    if (favFile.exists()) return favFile;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    @NonNull
    public String resolveCanonicalStickerCode(@NonNull Message message) {
        String text = message.getText() != null ? message.getText().trim() : "";
        if (text.startsWith("[STICKER_FILE:") || text.startsWith("[FILE:")) {
            return text;
        }
        if (text.startsWith("[STICKER:")) {
            return text;
        }
        StickerItem catalogMatch = findCatalogOrDbStickerByCode(text);
        if (catalogMatch != null && !"[sticker]".equalsIgnoreCase(text)) {
            return catalogMatch.code;
        }
        File favFile = resolveAndPersistCanonicalStickerFile(message);
        if (favFile != null) {
            return "[STICKER_FILE:" + favFile.getAbsolutePath() + "]";
        }
        if (message.getLocalFilePath() != null && !message.getLocalFilePath().isEmpty()) {
            return "[STICKER_FILE:" + message.getLocalFilePath() + "]";
        }
        return text.isEmpty() ? "[STICKER:cypher_lit]" : text;
    }

    public boolean isFavoriteStickerMessage(@NonNull Message message) {
        ensureTablesExist();
        String canonicalCode = resolveCanonicalStickerCode(message);
        if (isFavoriteSticker(canonicalCode)) return true;
        if (message.getLocalFilePath() != null) {
            if (isFavoriteSticker("[FILE:" + message.getLocalFilePath() + "]")
                    || isFavoriteSticker("[STICKER_FILE:" + message.getLocalFilePath() + "]")) {
                return true;
            }
        }
        return false;
    }

    public boolean addFavoriteStickerFromMessage(@NonNull Message message) {
        ensureTablesExist();
        String canonicalCode = resolveCanonicalStickerCode(message);
        if (canonicalCode.isEmpty()) return false;
        StickerItem catalogMatch = findCatalogOrDbStickerByCode(canonicalCode);
        if (catalogMatch != null) {
            addFavoriteSticker(catalogMatch);
            return true;
        }
        File favFile = resolveAndPersistCanonicalStickerFile(message);
        String localPath = favFile != null ? favFile.getAbsolutePath() : message.getLocalFilePath();
        boolean isAnim = favFile == null || VeilStickerGifEngine.isAnimatedStickerFile(favFile);
        StickerItem custom = new StickerItem(
                "fav_" + Math.abs(canonicalCode.hashCode()),
                "favorites",
                canonicalCode,
                "⭐",
                "STICKER",
                0xFF6366F1,
                0xFF38BDF8,
                0,
                localPath,
                isAnim
        );
        addFavoriteSticker(custom);
        return true;
    }

    public boolean toggleFavoriteStickerMessage(@NonNull Message message) {
        ensureTablesExist();
        String canonicalCode = resolveCanonicalStickerCode(message);
        if (isFavoriteStickerMessage(message)) {
            removeFavoriteSticker(canonicalCode);
            if (message.getLocalFilePath() != null) {
                removeFavoriteSticker("[FILE:" + message.getLocalFilePath() + "]");
                removeFavoriteSticker("[STICKER_FILE:" + message.getLocalFilePath() + "]");
            }
            return false;
        } else {
            return addFavoriteStickerFromMessage(message);
        }
    }

    @Nullable
    public File resolveAndPersistCanonicalGifFile(@NonNull Message message) {
        File resolved = null;
        if (message.getLocalFilePath() != null && !message.getLocalFilePath().isEmpty()) {
            File f = new File(message.getLocalFilePath());
            if (f.exists() && f.length() > 16) resolved = f;
        }
        if (resolved == null) {
            String[] exts = new String[]{".gif", ".webp", ".mp4", ".jpg"};
            for (String ext : exts) {
                File dec = com.m3.veil.utils.FileMediaManager.getDecryptedFile(appContext, message, ext);
                if (dec != null && dec.exists() && dec.length() > 16) {
                    resolved = dec;
                    break;
                }
            }
        }
        if (resolved == null) {
            try {
                File inlineFile = com.m3.veil.manager.VeilMessagesController.decodeInlineMediaToFile(
                        appContext, message.getChatId(), message);
                if (inlineFile != null && inlineFile.exists() && inlineFile.length() > 16) {
                    resolved = inlineFile;
                }
            } catch (Throwable ignored) {}
        }
        if (resolved != null && resolved.exists()) {
            String hash = computeFileHash(resolved);
            File favGif = new File(getPackCacheDir("favorite_gifs"), "fav_gif_" + hash + ".gif");
            copyFileQuietly(resolved, favGif);
            return favGif.exists() ? favGif : resolved;
        }
        return null;
    }

    @NonNull
    public String resolveCanonicalGifCode(@NonNull Message message) {
        String text = message.getText() != null ? message.getText().trim() : "";
        if (text.startsWith("[GIF:") || text.startsWith("[GIF_FILE:")) {
            return text;
        }
        File favGif = resolveAndPersistCanonicalGifFile(message);
        if (favGif != null) {
            return "[GIF_FILE:" + favGif.getAbsolutePath() + "]";
        }
        if (message.getLocalFilePath() != null && !message.getLocalFilePath().isEmpty()) {
            return "[GIF_FILE:" + message.getLocalFilePath() + "]";
        }
        return text;
    }

    public boolean isFavoriteGifFile(@Nullable File gifFile) {
        if (gifFile == null || !gifFile.exists()) return false;
        String hash = computeFileHash(gifFile);
        File favGif = new File(getPackCacheDir("favorite_gifs"), "fav_gif_" + hash + ".gif");
        return isFavoriteGif("[GIF_FILE:" + favGif.getAbsolutePath() + "]")
                || isFavoriteGif("[GIF_FILE:" + gifFile.getAbsolutePath() + "]");
    }

    public boolean toggleFavoriteGifFile(@Nullable File gifFile) {
        if (gifFile == null || !gifFile.exists()) return false;
        String hash = computeFileHash(gifFile);
        File favGif = new File(getPackCacheDir("favorite_gifs"), "fav_gif_" + hash + ".gif");
        String canonicalCode = "[GIF_FILE:" + favGif.getAbsolutePath() + "]";
        if (isFavoriteGifFile(gifFile)) {
            removeFavoriteGif(canonicalCode);
            removeFavoriteGif("[GIF_FILE:" + gifFile.getAbsolutePath() + "]");
            return false;
        } else {
            copyFileQuietly(gifFile, favGif);
            addFavoriteGif(new VeilStickerGifEngine.GifPreset(
                    canonicalCode, "Favorite GIF", "Favorites", "🎬", "",
                    0xFF1E293B, 0xFF0F172A, 0xFF38BDF8, 320, 240, 0
            ));
            return true;
        }
    }

    public boolean isFavoriteGifMessage(@NonNull Message message) {
        ensureTablesExist();
        String code = resolveCanonicalGifCode(message);
        if (isFavoriteGif(code)) return true;
        if (message.getLocalFilePath() != null && isFavoriteGif("[GIF_FILE:" + message.getLocalFilePath() + "]")) {
            return true;
        }
        return false;
    }

    public boolean toggleFavoriteGifMessage(@NonNull Message message) {
        ensureTablesExist();
        String code = resolveCanonicalGifCode(message);
        if (isFavoriteGifMessage(message)) {
            removeFavoriteGif(code);
            if (message.getLocalFilePath() != null) {
                removeFavoriteGif("[GIF_FILE:" + message.getLocalFilePath() + "]");
            }
            return false;
        } else {
            VeilStickerGifEngine.GifPreset preset = VeilStickerGifEngine.findGifPresetByCode(code);
            if (preset != null) {
                addFavoriteGif(preset);
            } else {
                addFavoriteGif(new VeilStickerGifEngine.GifPreset(
                        code, "Favorite GIF", "Favorites", "🎬", "",
                        0xFF1E293B, 0xFF0F172A, 0xFF38BDF8, 320, 240, 0
                ));
            }
            return true;
        }
    }

}
