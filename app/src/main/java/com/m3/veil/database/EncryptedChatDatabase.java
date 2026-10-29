package com.m3.veil.database;

import android.content.ContentValues;
import android.content.Context;
import android.util.Log;

import com.google.firebase.Timestamp;
import com.m3.veil.model.CallRecord;
import com.m3.veil.model.Chat;
import com.m3.veil.model.Message;
import com.m3.veil.model.Post;

import net.sqlcipher.Cursor;
import net.sqlcipher.database.SQLiteDatabase;
import net.sqlcipher.database.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import com.m3.veil.manager.StatusController;

import java.io.File;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 100% Client-Side Local Encrypted Database using SQLCipher with 256-bit AES encryption.
 * Stores all chats, messages, VeilBoard posts/moments, call logs, and private vault notes
 * locally on device. Zero plaintext or cloud message storage.
 */
public class EncryptedChatDatabase extends SQLiteOpenHelper {

    private static final String TAG = "EncryptedChatDatabase";
    private static final String DATABASE_NAME = "veil_vault_encrypted.db";
    private static final int DATABASE_VERSION = 3;

    // Table: chats
    private static final String TABLE_CHATS = "chats";
    private static final String COL_CHAT_ID = "id";
    private static final String COL_CHAT_NAME = "name";
    private static final String COL_CHAT_LAST_MESSAGE = "last_message";
    private static final String COL_CHAT_LAST_MESSAGE_TIME = "last_message_time";
    private static final String COL_CHAT_UNREAD_COUNT = "unread_count";
    private static final String COL_CHAT_IS_GROUP = "is_group";
    private static final String COL_CHAT_AVATAR = "avatar";
    private static final String COL_CHAT_TYPE = "type";
    private static final String COL_CHAT_PARTICIPANTS = "participants";
    private static final String COL_CHAT_VANISH_TIMER = "vanish_timer";
    private static final String COL_CHAT_VANISH_MODE = "vanish_mode";
    private static final String COL_CHAT_IS_BLOCKED = "is_blocked";
    private static final String COL_CHAT_BLOCKED_BY = "blocked_by";
    private static final String COL_CHAT_PINNED = "is_pinned";
    private static final String COL_CHAT_MUTED = "is_muted";
    private static final String COL_CHAT_ARCHIVED = "is_archived";
    private static final String COL_CHAT_SECRET = "is_secret";
    private static final String COL_CHAT_LOCK_PIN = "chat_lock_pin";
    private static final String COL_CHAT_WALLPAPER = "wallpaper_color";
    private static final String COL_CHAT_SLOW_MODE = "slow_mode_sec";
    private static final String COL_CHAT_GROUP_RULES = "group_rules";
    private static final String COL_CHAT_PINNED_MSG_ID = "pinned_msg_id";
    private static final String COL_CHAT_PINNED_MSG_TEXT = "pinned_msg_text";
    private static final String COL_CHAT_TRUST_SCORE = "trust_score";
    private static final String COL_CHAT_GROUP_EXPIRES = "group_expires_at";
    private static final String COL_CHAT_ADMIN_ID = "admin_id";
    private static final String COL_CHAT_ADMIN_IDS = "admin_ids";
    private static final String COL_CHAT_DESCRIPTION = "description";
    private static final String COL_CHAT_IS_PUBLIC = "is_public";
    private static final String COL_CHAT_HANDLE = "handle";

    // Table: messages
    private static final String TABLE_MESSAGES = "messages";
    private static final String COL_MSG_ID = "id";
    private static final String COL_MSG_CHAT_ID = "chat_id";
    private static final String COL_MSG_SENDER_ID = "sender_id";
    private static final String COL_MSG_RECEIVER_ID = "receiver_id";
    private static final String COL_MSG_TEXT = "text";
    private static final String COL_MSG_TYPE = "type";
    private static final String COL_MSG_TIMESTAMP = "timestamp";
    private static final String COL_MSG_READ = "is_read";
    private static final String COL_MSG_DELIVERED = "is_delivered";
    private static final String COL_MSG_PENDING = "is_pending";
    private static final String COL_MSG_LOCAL_PATH = "local_file_path";
    private static final String COL_MSG_MEDIA_URL = "media_url";
    private static final String COL_MSG_THUMBNAIL = "thumbnail";
    private static final String COL_MSG_EXPIRES_AT = "expires_at";
    private static final String COL_MSG_REPLY_ID = "reply_to_id";
    private static final String COL_MSG_REPLY_NAME = "reply_sender_name";
    private static final String COL_MSG_REACTIONS = "reactions_json";
    private static final String COL_MSG_EDITED = "is_edited";
    private static final String COL_MSG_BLINK = "is_blink";
    private static final String COL_MSG_BLINK_VIEWED = "is_blink_viewed";
    private static final String COL_MSG_STARRED = "is_starred";
    private static final String COL_MSG_PINNED = "is_pinned";
    private static final String COL_MSG_VOICE_FILTER = "voice_filter";
    private static final String COL_MSG_POLL_JSON = "poll_json";
    private static final String COL_MSG_SENDER_NAME = "sender_name";
    private static final String COL_MSG_VANISH_TIMER = "vanish_timer";
    private static final String COL_MSG_VANISH_MODE = "vanish_mode";
    private static final String COL_MSG_VIEW_COUNT = "view_count";
    private static final String COL_MSG_COMMENT_COUNT = "comment_count";

    // Table: posts (VeilBoard, Moments, Confessions, Polls, Confession Jar)
    private static final String TABLE_POSTS = "posts";
    private static final String COL_POST_ID = "id";
    private static final String COL_POST_AUTHOR_ID = "author_id";
    private static final String COL_POST_AUTHOR_NAME = "author_username";
    private static final String COL_POST_AUTHOR_AVATAR = "author_avatar";
    private static final String COL_POST_CONTENT = "content";
    private static final String COL_POST_TIMESTAMP = "timestamp";
    private static final String COL_POST_LIKES_COUNT = "likes_count";
    private static final String COL_POST_COMMENTS_COUNT = "comments_count";
    private static final String COL_POST_HASHTAGS = "hashtags";
    private static final String COL_POST_TYPE = "type";
    private static final String COL_POST_IMAGE_URL = "image_url";
    private static final String COL_POST_LIKED_BY = "liked_by";
    private static final String COL_POST_MOOD = "mood_badge";
    private static final String COL_POST_CATEGORY = "category";
    private static final String COL_POST_POLL_JSON = "poll_json";
    private static final String COL_POST_EXPIRES_AT = "expires_at";
    private static final String COL_POST_COMMENTS_JSON = "comments_json";

    // Table: calls (100% Local Encrypted Call History)
    private static final String TABLE_CALLS = "calls";
    private static final String COL_CALL_ID = "id";
    private static final String COL_CALL_PEER_ID = "peer_id";
    private static final String COL_CALL_PEER_NAME = "peer_name";
    private static final String COL_CALL_TYPE = "type";
    private static final String COL_CALL_TIMESTAMP = "timestamp";
    private static final String COL_CALL_DURATION = "duration";
    private static final String COL_CALL_CALLER_ID = "caller_id";
    private static final String COL_CALL_CALLER_NAME = "caller_name";
    private static final String COL_CALL_RECEIVER_ID = "receiver_id";
    private static final String COL_CALL_RECEIVER_NAME = "receiver_name";
    private static final String COL_CALL_STATUS = "status";
    private static final String COL_CALL_IS_OUTGOING = "is_outgoing";

    // Table: notes_vault (Private Notes Vault, Anonymous Diary, Future-Self Messages)
    private static final String TABLE_NOTES = "notes_vault";
    private static final String COL_NOTE_ID = "id";
    private static final String COL_NOTE_TITLE = "title";
    private static final String COL_NOTE_CONTENT = "content";
    private static final String COL_NOTE_TYPE = "note_type"; // "vault", "diary", "future_self"
    private static final String COL_NOTE_TIMESTAMP = "timestamp";
    private static final String COL_NOTE_UNLOCK_AT = "unlock_at";

    // Table: outbox_queue (Local SQLite P2P Outbox for offline/background delivery)
    private static final String TABLE_OUTBOX = "outbox_queue";
    private static final String COL_OUTBOX_ID = "id";
    private static final String COL_OUTBOX_CHAT_ID = "chat_id";
    private static final String COL_OUTBOX_RECIPIENT_ID = "recipient_id";
    private static final String COL_OUTBOX_PACKET_TYPE = "packet_type";
    private static final String COL_OUTBOX_PAYLOAD = "payload";
    private static final String COL_OUTBOX_TIMESTAMP = "timestamp";
    private static final String COL_OUTBOX_RETRY_COUNT = "retry_count";
    private static final String COL_OUTBOX_LAST_ATTEMPT = "last_attempt";
    private static final String COL_OUTBOX_ENVELOPE_ID = "envelope_id";

    // Table: file_transfers (Resumable P2P File Transfers with BitSet chunk maps)
    private static final String TABLE_FILE_TRANSFERS = "file_transfers";
    private static final String COL_FT_ID = "transfer_id";
    private static final String COL_FT_MSG_ID = "message_id";
    private static final String COL_FT_FILE_PATH = "file_path";
    private static final String COL_FT_FILE_NAME = "file_name";
    private static final String COL_FT_MIME_TYPE = "mime_type";
    private static final String COL_FT_TOTAL_BYTES = "total_bytes";
    private static final String COL_FT_CHUNK_SIZE = "chunk_size";
    private static final String COL_FT_TOTAL_CHUNKS = "total_chunks";
    private static final String COL_FT_BITSET = "bitset_bytes";
    private static final String COL_FT_IS_UPLOAD = "is_upload";
    private static final String COL_FT_STATUS = "status";
    private static final String COL_FT_PEER_ID = "peer_id";
    private static final String COL_FT_UPDATED_AT = "updated_at";

    // Table: mesh_envelopes (Pure P2P Mesh Store-and-Forward Envelope Cache)
    private static final String TABLE_MESH_ENVELOPES = "mesh_envelopes";
    private static final String COL_MESH_ID = "envelope_id";
    private static final String COL_MESH_MAILBOX = "blind_mailbox_hash";
    private static final String COL_MESH_PAYLOAD = "sealed_payload";
    private static final String COL_MESH_HOP_COUNT = "hop_count";
    private static final String COL_MESH_MAX_HOPS = "max_hops";
    private static final String COL_MESH_CREATED_AT = "created_at";
    private static final String COL_MESH_EXPIRES_AT = "expires_at";
    private static final String COL_MESH_ORIGIN_PEER = "origin_peer_id";

    // Table: veil_statuses (100% Pure P2P WebRTC Status Reels)
    public static final String TABLE_STATUSES = "veil_statuses";
    public static final String COL_STATUS_ID = "status_id";
    public static final String COL_STATUS_AUTHOR_ID = "author_id";
    public static final String COL_STATUS_AUTHOR_NAME = "author_name";
    public static final String COL_STATUS_CONTENT_TEXT = "content_text";
    public static final String COL_STATUS_MEDIA_TYPE = "media_type";
    public static final String COL_STATUS_MEDIA_FILE_PATH = "media_file_path";
    public static final String COL_STATUS_ENCRYPTED_FILE_PATH = "encrypted_file_path";
    public static final String COL_STATUS_THUMB_BLOB = "thumb_blob";
    public static final String COL_STATUS_BG_COLOR_HEX = "bg_color_hex";
    public static final String COL_STATUS_FONT_STYLE_INDEX = "font_style_index";
    public static final String COL_STATUS_TIMESTAMP_MS = "timestamp_ms";
    public static final String COL_STATUS_EXPIRES_AT_MS = "expires_at_ms";
    public static final String COL_STATUS_TOTAL_BYTES = "total_bytes";
    public static final String COL_STATUS_TOTAL_CHUNKS = "total_chunks";
    public static final String COL_STATUS_PRIVACY_ACL_MODE = "privacy_acl_mode";
    public static final String COL_STATUS_IS_MINE = "is_mine";
    public static final String COL_STATUS_IS_VIEWED = "is_viewed";
    public static final String COL_STATUS_VIEW_COUNT = "view_count";
    public static final String COL_STATUS_AES_KEY_WRAPPED = "aes_key_wrapped";
    public static final String COL_STATUS_IV_BASE64 = "iv_base64";

    // Table: veil_status_views (P2P Status View Receipts)
    public static final String TABLE_STATUS_VIEWS = "veil_status_views";
    public static final String COL_SV_VIEW_ID = "view_id";
    public static final String COL_SV_STATUS_ID = "status_id";
    public static final String COL_SV_VIEWER_ID = "viewer_id";
    public static final String COL_SV_VIEWER_NAME = "viewer_name";
    public static final String COL_SV_VIEWER_AVATAR_URL = "viewer_avatar_url";
    public static final String COL_SV_VIEWED_AT_MS = "viewed_at_ms";
    public static final String COL_SV_SIGNED_RECEIPT_BLOB = "signed_receipt_blob";

    // Table: veil_status_chunk_bitmaps (Resumable 16KB Status Chunk Bitmaps)
    public static final String TABLE_STATUS_CHUNKS = "veil_status_chunk_bitmaps";
    public static final String COL_SC_STATUS_ID = "status_id";
    public static final String COL_SC_CHUNK_BITMAP_BLOB = "chunk_bitmap_blob";
    public static final String COL_SC_COMPLETED_CHUNKS = "completed_chunks";
    public static final String COL_SC_TOTAL_CHUNKS = "total_chunks";
    public static final String COL_SC_UPDATED_AT_MS = "updated_at_ms";

    // Table: veil_status_gossip_queue (Outbox queue for status announcement gossip)
    public static final String TABLE_STATUS_GOSSIP_QUEUE = "veil_status_gossip_queue";
    public static final String COL_SGQ_QUEUE_ID = "queue_id";
    public static final String COL_SGQ_STATUS_ID = "status_id";
    public static final String COL_SGQ_TARGET_PEER_ID = "target_peer_id";
    public static final String COL_SGQ_PAYLOAD_BLOB = "packet_payload_blob";
    public static final String COL_SGQ_RETRY_COUNT = "retry_count";
    public static final String COL_SGQ_LAST_ATTEMPT_MS = "last_attempt_ms";

    // Table: scheduled_messages (#80, #83)
    public static final String TABLE_SCHEDULED_MESSAGES = "scheduled_messages";
    public static final String COL_SCHED_ID = "id";
    public static final String COL_SCHED_DIALOG_ID = "dialog_id";
    public static final String COL_SCHED_SENDER_ID = "sender_id";
    public static final String COL_SCHED_SENDER_NAME = "sender_name";
    public static final String COL_SCHED_TEXT = "text";
    public static final String COL_SCHED_MEDIA_URL = "media_url";
    public static final String COL_SCHED_LOCAL_PATH = "local_path";
    public static final String COL_SCHED_MSG_TYPE = "msg_type";
    public static final String COL_SCHED_SCHEDULED_AT = "scheduled_at";
    public static final String COL_SCHED_EXPIRES_AT = "expires_at";
    public static final String COL_SCHED_STATUS = "status";
    public static final String COL_SCHED_IS_CHANNEL = "is_channel_broadcast";
    public static final String COL_SCHED_CREATED_AT = "created_at";

    // Table: sovereign_identity_manifest
    public static final String TABLE_SOVEREIGN_MANIFEST = "sovereign_identity_manifest";
    public static final String COL_SM_UID = "uid";
    public static final String COL_SM_USERNAME = "username";
    public static final String COL_SM_DISPLAY_NAME = "display_name";
    public static final String COL_SM_BIO = "bio";
    public static final String COL_SM_AVATAR_BASE64 = "avatar_base64";
    public static final String COL_SM_MOOD = "mood";
    public static final String COL_SM_XP = "xp";
    public static final String COL_SM_KARMA = "karma";
    public static final String COL_SM_VEIL_COINS = "veil_coins";
    public static final String COL_SM_PERSONAS_JSON = "personas_json";
    public static final String COL_SM_PUBLIC_KEY = "public_key_base64";
    public static final String COL_SM_UPDATED_AT = "updated_at";
    public static final String COL_SM_SIGNATURE = "signature";

    // Table: read_later_reminders (#178)
    public static final String TABLE_REMINDERS = "read_later_reminders";
    public static final String COL_REM_ID = "id";
    public static final String COL_REM_MESSAGE_ID = "message_id";
    public static final String COL_REM_DIALOG_ID = "dialog_id";
    public static final String COL_REM_TITLE = "title";
    public static final String COL_REM_SNIPPET = "snippet";
    public static final String COL_REM_REMIND_AT = "remind_at";
    public static final String COL_REM_CREATED_AT = "created_at";
    public static final String COL_REM_STATUS = "status";

    // Table: group_subgroups (#71)
    public static final String TABLE_GROUP_SUBGROUPS = "group_subgroups";
    public static final String COL_SUB_ID = "subgroup_id";
    public static final String COL_SUB_PARENT_GROUP_ID = "parent_group_id";
    public static final String COL_SUB_TITLE = "title";
    public static final String COL_SUB_DESCRIPTION = "description";
    public static final String COL_SUB_INVITE_KEY = "invite_key";
    public static final String COL_SUB_ROLE_REQUIRED = "role_required";
    public static final String COL_SUB_IS_SECRET = "is_secret";
    public static final String COL_SUB_CREATED_AT = "created_at";

    // Table: group_events (#68, #225)
    public static final String TABLE_GROUP_EVENTS = "group_events";
    public static final String COL_EVT_ID = "event_id";
    public static final String COL_EVT_GROUP_ID = "group_id";
    public static final String COL_EVT_CREATOR_ID = "creator_id";
    public static final String COL_EVT_CREATOR_NAME = "creator_name";
    public static final String COL_EVT_TITLE = "title";
    public static final String COL_EVT_DESCRIPTION = "description";
    public static final String COL_EVT_LOCATION_OR_LINK = "location_or_link";
    public static final String COL_EVT_START_TIME = "start_time";
    public static final String COL_EVT_END_TIME = "end_time";
    public static final String COL_EVT_RSVP_COUNT = "rsvp_count";
    public static final String COL_EVT_USER_RSVP = "user_rsvp_status";
    public static final String COL_EVT_COUNTDOWN_ACTIVE = "countdown_active";
    public static final String COL_EVT_CREATED_AT = "created_at";

    // Table: channel_admin_keys (#82)
    public static final String TABLE_CHANNEL_ADMIN_KEYS = "channel_admin_keys";
    public static final String COL_CAK_CHANNEL_ID = "channel_id";
    public static final String COL_CAK_ADMIN_ALIAS = "admin_alias";
    public static final String COL_CAK_BLIND_PUBLIC_KEY = "blind_public_key";
    public static final String COL_CAK_BLIND_PRIVATE_KEY = "blind_private_key";
    public static final String COL_CAK_ROTATION_EPOCH = "rotation_epoch";
    public static final String COL_CAK_LAST_ROTATED = "last_rotated";

    // Table: veilboard_reposts (#92)
    public static final String TABLE_REPOSTS = "veilboard_reposts";
    public static final String COL_REP_ID = "repost_id";
    public static final String COL_REP_ORIGINAL_POST_ID = "original_post_id";
    public static final String COL_REP_ORIGINAL_AUTHOR_ID = "original_author_id";
    public static final String COL_REP_ORIGINAL_AUTHOR_NAME = "original_author_name";
    public static final String COL_REP_REPOSTER_PERSONA_ID = "reposter_persona_id";
    public static final String COL_REP_REPOSTER_NAME = "reposter_name";
    public static final String COL_REP_QUOTE_TEXT = "quote_text";
    public static final String COL_REP_TIMESTAMP = "timestamp";

    // Table: moderation_transparency_log (#194, #196)
    public static final String TABLE_MODERATION_TRANSPARENCY_LOG = "moderation_transparency_log";
    public static final String COL_MTL_LOG_ID = "log_id";
    public static final String COL_MTL_POST_ID = "post_id";
    public static final String COL_MTL_REASON = "reason";
    public static final String COL_MTL_JUROR_COUNT = "juror_count";
    public static final String COL_MTL_VERDICT = "verdict";
    public static final String COL_MTL_CONTENT_HASH = "content_hash";
    public static final String COL_MTL_TIMESTAMP_MS = "timestamp_ms";
    public static final String COL_MTL_SIGNATURE = "signature";

    public interface DatabaseChangeListener {
        void onMessagesUpdated(String chatId);
        void onChatsUpdated();
        default void onPostsUpdated() {}
        default void onCallsUpdated() {}
        default void onStatusesUpdated() {}
    }

    private static EncryptedChatDatabase instance;
    private static final Map<String, EncryptedChatDatabase> sPersonaInstances = new ConcurrentHashMap<>();

    private final Context context;
    private final String dbName;
    private final byte[] passphrase;
    private final List<DatabaseChangeListener> listeners = new CopyOnWriteArrayList<>();

    // RAM Cache Layer for 0ms reads (modeled after Telegram MessagesStorage in-memory caches)
    private final Map<String, Chat> ramChatCache = new ConcurrentHashMap<>();
    private final Map<String, List<Message>> ramMessageListCache = new ConcurrentHashMap<>();
    private final Map<String, Message> ramSingleMessageCache = new ConcurrentHashMap<>();

    // Prepared Statement Pool
    private final Map<String, net.sqlcipher.database.SQLiteStatement> statementPool = new ConcurrentHashMap<>();

    public static synchronized EncryptedChatDatabase getInstance(Context context, String personaId, boolean isDecoy) {
        String dbKey;
        if (isDecoy) {
            dbKey = "veil_vault_decoy.db";
        } else if (personaId != null && !personaId.trim().isEmpty() && !"default".equals(personaId)) {
            dbKey = "veil_vault_persona_" + personaId.trim() + ".db";
        } else {
            dbKey = DATABASE_NAME; // "veil_vault_encrypted.db"
        }

        EncryptedChatDatabase db = sPersonaInstances.get(dbKey);
        if (db == null) {
            db = new EncryptedChatDatabase(context.getApplicationContext(), dbKey);
            sPersonaInstances.put(dbKey, db);
        }
        return db;
    }

    public static synchronized EncryptedChatDatabase getInstance(Context context, String personaId) {
        return getInstance(context, personaId, false);
    }

    public static synchronized EncryptedChatDatabase getInstance(Context context) {
        boolean isDecoy = false;
        String personaId = null;
        try {
            android.content.SharedPreferences sp = context.getSharedPreferences("veil_prefs", Context.MODE_PRIVATE);
            android.content.SharedPreferences fsp = context.getSharedPreferences("VeilFeaturePrefs", Context.MODE_PRIVATE);
            isDecoy = sp.getBoolean("decoy_mode_active", fsp.getBoolean("decoy_mode_active", false));
            personaId = sp.getString("active_persona_id", fsp.getString("active_persona_id", null));
        } catch (Exception ignored) {}
        return getInstance(context, personaId, isDecoy);
    }

    public static synchronized EncryptedChatDatabase switchActiveVault(Context context, String personaId, boolean isDecoy) {
        try {
            context.getSharedPreferences("veil_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("decoy_mode_active", isDecoy)
                    .putString("active_persona_id", personaId != null ? personaId : "persona_primary")
                    .apply();
            context.getSharedPreferences("VeilFeaturePrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("decoy_mode_active", isDecoy)
                    .putString("active_persona_id", personaId != null ? personaId : "persona_primary")
                    .apply();
        } catch (Exception ignored) {}
        return getInstance(context, personaId, isDecoy);
    }

    private EncryptedChatDatabase(Context context, String dbName) {
        super(context, dbName, null, DATABASE_VERSION);
        this.context = context;
        this.dbName = dbName;
        SQLiteDatabase.loadLibs(context);
        this.passphrase = DatabaseKeyManager.getDatabasePassphrase(context);
    }

    public synchronized net.sqlcipher.database.SQLiteStatement getOrCompileStatement(String sql) {
        net.sqlcipher.database.SQLiteStatement stmt = statementPool.get(sql);
        if (stmt == null) {
            try {
                SQLiteDatabase db = getEncryptedDb();
                stmt = db.compileStatement(sql);
                statementPool.put(sql, stmt);
            } catch (Exception e) {
                Log.e(TAG, "Error compiling SQL statement: " + sql, e);
            }
        }
        return stmt;
    }

    private SQLiteDatabase getEncryptedDb() {
        return getWritableDatabase(passphrase);
    }

    public void addListener(DatabaseChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(DatabaseChangeListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    /**
     * Shrinks SQLCipher page cache and statement pool memory on OS memory pressure.
     */
    public void trimMemory(int level) {
        try {
            net.sqlcipher.database.SQLiteDatabase db = getEncryptedDb();
            if (db != null && db.isOpen()) {
                db.execSQL("PRAGMA shrink_memory;");
            }
        } catch (Throwable ignored) { }
    }

    private void notifyMessagesChanged(String chatId) {
        for (DatabaseChangeListener l : listeners) {
            try {
                l.onMessagesUpdated(chatId);
            } catch (Exception ignored) {}
        }
    }

    private void notifyChatsChanged() {
        for (DatabaseChangeListener l : listeners) {
            try {
                l.onChatsUpdated();
            } catch (Exception ignored) {}
        }
    }

    private void notifyPostsChanged() {
        for (DatabaseChangeListener l : listeners) {
            try {
                l.onPostsUpdated();
            } catch (Exception ignored) {}
        }
    }

    private void notifyCallsChanged() {
        for (DatabaseChangeListener l : listeners) {
            try {
                l.onCallsUpdated();
            } catch (Exception ignored) {}
        }
    }

    private void notifyStatusesChanged() {
        for (DatabaseChangeListener l : listeners) {
            try {
                l.onStatusesUpdated();
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_CHATS + " (" +
                COL_CHAT_ID + " TEXT PRIMARY KEY, " +
                COL_CHAT_NAME + " TEXT, " +
                COL_CHAT_LAST_MESSAGE + " TEXT, " +
                COL_CHAT_LAST_MESSAGE_TIME + " INTEGER, " +
                COL_CHAT_UNREAD_COUNT + " INTEGER DEFAULT 0, " +
                COL_CHAT_IS_GROUP + " INTEGER DEFAULT 0, " +
                COL_CHAT_AVATAR + " TEXT, " +
                COL_CHAT_TYPE + " TEXT, " +
                COL_CHAT_PARTICIPANTS + " TEXT, " +
                COL_CHAT_VANISH_TIMER + " INTEGER DEFAULT 0, " +
                COL_CHAT_VANISH_MODE + " TEXT, " +
                COL_CHAT_IS_BLOCKED + " INTEGER DEFAULT 0, " +
                COL_CHAT_BLOCKED_BY + " TEXT, " +
                COL_CHAT_PINNED + " INTEGER DEFAULT 0, " +
                COL_CHAT_MUTED + " INTEGER DEFAULT 0, " +
                COL_CHAT_ARCHIVED + " INTEGER DEFAULT 0, " +
                COL_CHAT_SECRET + " INTEGER DEFAULT 0, " +
                COL_CHAT_LOCK_PIN + " TEXT, " +
                COL_CHAT_WALLPAPER + " TEXT, " +
                COL_CHAT_SLOW_MODE + " INTEGER DEFAULT 0, " +
                COL_CHAT_GROUP_RULES + " TEXT, " +
                COL_CHAT_PINNED_MSG_ID + " TEXT, " +
                COL_CHAT_PINNED_MSG_TEXT + " TEXT, " +
                COL_CHAT_TRUST_SCORE + " INTEGER DEFAULT 10, " +
                COL_CHAT_GROUP_EXPIRES + " INTEGER DEFAULT 0" +
                ");");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_MESSAGES + " (" +
                COL_MSG_ID + " TEXT PRIMARY KEY, " +
                COL_MSG_CHAT_ID + " TEXT NOT NULL, " +
                COL_MSG_SENDER_ID + " TEXT NOT NULL, " +
                COL_MSG_RECEIVER_ID + " TEXT, " +
                COL_MSG_TEXT + " TEXT, " +
                COL_MSG_TYPE + " TEXT, " +
                COL_MSG_TIMESTAMP + " INTEGER, " +
                COL_MSG_READ + " INTEGER DEFAULT 0, " +
                COL_MSG_DELIVERED + " INTEGER DEFAULT 0, " +
                COL_MSG_PENDING + " INTEGER DEFAULT 0, " +
                COL_MSG_LOCAL_PATH + " TEXT, " +
                COL_MSG_MEDIA_URL + " TEXT, " +
                COL_MSG_THUMBNAIL + " TEXT, " +
                COL_MSG_EXPIRES_AT + " INTEGER DEFAULT 0, " +
                COL_MSG_REPLY_ID + " TEXT, " +
                COL_MSG_REPLY_NAME + " TEXT, " +
                COL_MSG_REACTIONS + " TEXT, " +
                COL_MSG_EDITED + " INTEGER DEFAULT 0, " +
                COL_MSG_BLINK + " INTEGER DEFAULT 0, " +
                COL_MSG_BLINK_VIEWED + " INTEGER DEFAULT 0, " +
                COL_MSG_STARRED + " INTEGER DEFAULT 0, " +
                COL_MSG_PINNED + " INTEGER DEFAULT 0, " +
                COL_MSG_VOICE_FILTER + " TEXT, " +
                COL_MSG_POLL_JSON + " TEXT, " +
                COL_MSG_SENDER_NAME + " TEXT, " +
                COL_MSG_VANISH_TIMER + " INTEGER DEFAULT 0, " +
                COL_MSG_VANISH_MODE + " TEXT" +
                ");");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_POSTS + " (" +
                COL_POST_ID + " TEXT PRIMARY KEY, " +
                COL_POST_AUTHOR_ID + " TEXT, " +
                COL_POST_AUTHOR_NAME + " TEXT, " +
                COL_POST_AUTHOR_AVATAR + " TEXT, " +
                COL_POST_CONTENT + " TEXT, " +
                COL_POST_TIMESTAMP + " INTEGER, " +
                COL_POST_LIKES_COUNT + " INTEGER DEFAULT 0, " +
                COL_POST_COMMENTS_COUNT + " INTEGER DEFAULT 0, " +
                COL_POST_HASHTAGS + " TEXT, " +
                COL_POST_TYPE + " TEXT, " +
                COL_POST_IMAGE_URL + " TEXT, " +
                COL_POST_LIKED_BY + " TEXT, " +
                COL_POST_MOOD + " TEXT, " +
                COL_POST_CATEGORY + " TEXT, " +
                COL_POST_POLL_JSON + " TEXT, " +
                COL_POST_EXPIRES_AT + " INTEGER DEFAULT 0, " +
                COL_POST_COMMENTS_JSON + " TEXT" +
                ");");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_CALLS + " (" +
                COL_CALL_ID + " TEXT PRIMARY KEY, " +
                COL_CALL_PEER_ID + " TEXT, " +
                COL_CALL_PEER_NAME + " TEXT, " +
                COL_CALL_TYPE + " TEXT, " +
                COL_CALL_TIMESTAMP + " INTEGER, " +
                COL_CALL_DURATION + " TEXT, " +
                COL_CALL_CALLER_ID + " TEXT, " +
                COL_CALL_CALLER_NAME + " TEXT, " +
                COL_CALL_RECEIVER_ID + " TEXT, " +
                COL_CALL_RECEIVER_NAME + " TEXT, " +
                COL_CALL_STATUS + " TEXT, " +
                COL_CALL_IS_OUTGOING + " INTEGER DEFAULT 0" +
                ");");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_NOTES + " (" +
                COL_NOTE_ID + " TEXT PRIMARY KEY, " +
                COL_NOTE_TITLE + " TEXT, " +
                COL_NOTE_CONTENT + " TEXT, " +
                COL_NOTE_TYPE + " TEXT, " +
                COL_NOTE_TIMESTAMP + " INTEGER, " +
                COL_NOTE_UNLOCK_AT + " INTEGER DEFAULT 0" +
                ");");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_OUTBOX + " (" +
                COL_OUTBOX_ID + " TEXT PRIMARY KEY, " +
                COL_OUTBOX_CHAT_ID + " TEXT, " +
                COL_OUTBOX_RECIPIENT_ID + " TEXT, " +
                COL_OUTBOX_PACKET_TYPE + " INTEGER, " +
                COL_OUTBOX_PAYLOAD + " BLOB, " +
                COL_OUTBOX_TIMESTAMP + " INTEGER, " +
                COL_OUTBOX_RETRY_COUNT + " INTEGER DEFAULT 0, " +
                COL_OUTBOX_LAST_ATTEMPT + " INTEGER DEFAULT 0, " +
                COL_OUTBOX_ENVELOPE_ID + " TEXT" +
                ");");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_FILE_TRANSFERS + " (" +
                COL_FT_ID + " TEXT PRIMARY KEY, " +
                COL_FT_MSG_ID + " TEXT, " +
                COL_FT_FILE_PATH + " TEXT, " +
                COL_FT_FILE_NAME + " TEXT, " +
                COL_FT_MIME_TYPE + " TEXT, " +
                COL_FT_TOTAL_BYTES + " INTEGER, " +
                COL_FT_CHUNK_SIZE + " INTEGER, " +
                COL_FT_TOTAL_CHUNKS + " INTEGER, " +
                COL_FT_BITSET + " BLOB, " +
                COL_FT_IS_UPLOAD + " INTEGER DEFAULT 0, " +
                COL_FT_STATUS + " TEXT, " +
                COL_FT_PEER_ID + " TEXT, " +
                COL_FT_UPDATED_AT + " INTEGER" +
                ");");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_MESH_ENVELOPES + " (" +
                COL_MESH_ID + " TEXT PRIMARY KEY, " +
                COL_MESH_MAILBOX + " TEXT, " +
                COL_MESH_PAYLOAD + " BLOB, " +
                COL_MESH_HOP_COUNT + " INTEGER DEFAULT 0, " +
                COL_MESH_MAX_HOPS + " INTEGER DEFAULT 3, " +
                COL_MESH_CREATED_AT + " INTEGER, " +
                COL_MESH_EXPIRES_AT + " INTEGER, " +
                COL_MESH_ORIGIN_PEER + " TEXT" +
                ");");

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_chat_timestamp ON " +
                TABLE_MESSAGES + " (" + COL_MSG_CHAT_ID + ", " + COL_MSG_TIMESTAMP + " ASC);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_posts_timestamp ON " +
                TABLE_POSTS + " (" + COL_POST_TIMESTAMP + " DESC);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_outbox_peer ON " +
                TABLE_OUTBOX + " (" + COL_OUTBOX_RECIPIENT_ID + ", " + COL_OUTBOX_TIMESTAMP + " ASC);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mesh_mailbox ON " +
                TABLE_MESH_ENVELOPES + " (" + COL_MESH_MAILBOX + ");");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mesh_expires ON " +
                TABLE_MESH_ENVELOPES + " (" + COL_MESH_EXPIRES_AT + ");");

        // 100% Pure P2P Status Reels Tables
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_STATUSES + " (" +
                COL_STATUS_ID + " TEXT PRIMARY KEY, " +
                COL_STATUS_AUTHOR_ID + " TEXT, " +
                COL_STATUS_AUTHOR_NAME + " TEXT, " +
                COL_STATUS_CONTENT_TEXT + " TEXT, " +
                COL_STATUS_MEDIA_TYPE + " TEXT, " +
                COL_STATUS_MEDIA_FILE_PATH + " TEXT, " +
                COL_STATUS_ENCRYPTED_FILE_PATH + " TEXT, " +
                COL_STATUS_THUMB_BLOB + " BLOB, " +
                COL_STATUS_BG_COLOR_HEX + " TEXT, " +
                COL_STATUS_FONT_STYLE_INDEX + " INTEGER DEFAULT 0, " +
                COL_STATUS_TIMESTAMP_MS + " INTEGER, " +
                COL_STATUS_EXPIRES_AT_MS + " INTEGER, " +
                COL_STATUS_TOTAL_BYTES + " INTEGER DEFAULT 0, " +
                COL_STATUS_TOTAL_CHUNKS + " INTEGER DEFAULT 0, " +
                COL_STATUS_PRIVACY_ACL_MODE + " INTEGER DEFAULT 0, " +
                COL_STATUS_IS_MINE + " INTEGER DEFAULT 0, " +
                COL_STATUS_IS_VIEWED + " INTEGER DEFAULT 0, " +
                COL_STATUS_VIEW_COUNT + " INTEGER DEFAULT 0, " +
                COL_STATUS_AES_KEY_WRAPPED + " TEXT, " +
                COL_STATUS_IV_BASE64 + " TEXT" +
                ");");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_STATUS_VIEWS + " (" +
                COL_SV_VIEW_ID + " TEXT PRIMARY KEY, " +
                COL_SV_STATUS_ID + " TEXT, " +
                COL_SV_VIEWER_ID + " TEXT, " +
                COL_SV_VIEWER_NAME + " TEXT, " +
                COL_SV_VIEWER_AVATAR_URL + " TEXT, " +
                COL_SV_VIEWED_AT_MS + " INTEGER, " +
                COL_SV_SIGNED_RECEIPT_BLOB + " BLOB" +
                ");");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_STATUS_CHUNKS + " (" +
                COL_SC_STATUS_ID + " TEXT PRIMARY KEY, " +
                COL_SC_CHUNK_BITMAP_BLOB + " BLOB, " +
                COL_SC_COMPLETED_CHUNKS + " INTEGER DEFAULT 0, " +
                COL_SC_TOTAL_CHUNKS + " INTEGER DEFAULT 0, " +
                COL_SC_UPDATED_AT_MS + " INTEGER" +
                ");");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_STATUS_GOSSIP_QUEUE + " (" +
                COL_SGQ_QUEUE_ID + " TEXT PRIMARY KEY, " +
                COL_SGQ_STATUS_ID + " TEXT, " +
                COL_SGQ_TARGET_PEER_ID + " TEXT, " +
                COL_SGQ_PAYLOAD_BLOB + " BLOB, " +
                COL_SGQ_RETRY_COUNT + " INTEGER DEFAULT 0, " +
                COL_SGQ_LAST_ATTEMPT_MS + " INTEGER DEFAULT 0" +
                ");");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_SCHEDULED_MESSAGES + " (" +
                COL_SCHED_ID + " TEXT PRIMARY KEY, " +
                COL_SCHED_DIALOG_ID + " TEXT NOT NULL, " +
                COL_SCHED_SENDER_ID + " TEXT NOT NULL, " +
                COL_SCHED_SENDER_NAME + " TEXT, " +
                COL_SCHED_TEXT + " TEXT, " +
                COL_SCHED_MEDIA_URL + " TEXT, " +
                COL_SCHED_LOCAL_PATH + " TEXT, " +
                COL_SCHED_MSG_TYPE + " TEXT, " +
                COL_SCHED_SCHEDULED_AT + " INTEGER NOT NULL, " +
                COL_SCHED_EXPIRES_AT + " INTEGER DEFAULT 0, " +
                COL_SCHED_STATUS + " TEXT DEFAULT 'PENDING', " +
                COL_SCHED_IS_CHANNEL + " INTEGER DEFAULT 0, " +
                COL_SCHED_CREATED_AT + " INTEGER" +
                ");");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_SOVEREIGN_MANIFEST + " (" +
                COL_SM_UID + " TEXT PRIMARY KEY, " +
                COL_SM_USERNAME + " TEXT, " +
                COL_SM_DISPLAY_NAME + " TEXT, " +
                COL_SM_BIO + " TEXT, " +
                COL_SM_AVATAR_BASE64 + " TEXT, " +
                COL_SM_MOOD + " TEXT, " +
                COL_SM_XP + " INTEGER, " +
                COL_SM_KARMA + " INTEGER, " +
                COL_SM_VEIL_COINS + " INTEGER, " +
                COL_SM_PERSONAS_JSON + " TEXT, " +
                COL_SM_PUBLIC_KEY + " TEXT, " +
                COL_SM_UPDATED_AT + " INTEGER, " +
                COL_SM_SIGNATURE + " TEXT" +
                ");");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_REMINDERS + " (" +
                COL_REM_ID + " TEXT PRIMARY KEY, " +
                COL_REM_MESSAGE_ID + " TEXT NOT NULL, " +
                COL_REM_DIALOG_ID + " TEXT NOT NULL, " +
                COL_REM_TITLE + " TEXT, " +
                COL_REM_SNIPPET + " TEXT, " +
                COL_REM_REMIND_AT + " INTEGER NOT NULL, " +
                COL_REM_CREATED_AT + " INTEGER, " +
                COL_REM_STATUS + " TEXT DEFAULT 'PENDING'" +
                ");");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_GROUP_SUBGROUPS + " (" +
                COL_SUB_ID + " TEXT PRIMARY KEY, " +
                COL_SUB_PARENT_GROUP_ID + " TEXT NOT NULL, " +
                COL_SUB_TITLE + " TEXT NOT NULL, " +
                COL_SUB_DESCRIPTION + " TEXT, " +
                COL_SUB_INVITE_KEY + " TEXT, " +
                COL_SUB_ROLE_REQUIRED + " TEXT DEFAULT 'MEMBER', " +
                COL_SUB_IS_SECRET + " INTEGER DEFAULT 0, " +
                COL_SUB_CREATED_AT + " INTEGER" +
                ");");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_GROUP_EVENTS + " (" +
                COL_EVT_ID + " TEXT PRIMARY KEY, " +
                COL_EVT_GROUP_ID + " TEXT NOT NULL, " +
                COL_EVT_CREATOR_ID + " TEXT NOT NULL, " +
                COL_EVT_CREATOR_NAME + " TEXT, " +
                COL_EVT_TITLE + " TEXT NOT NULL, " +
                COL_EVT_DESCRIPTION + " TEXT, " +
                COL_EVT_LOCATION_OR_LINK + " TEXT, " +
                COL_EVT_START_TIME + " INTEGER NOT NULL, " +
                COL_EVT_END_TIME + " INTEGER DEFAULT 0, " +
                COL_EVT_RSVP_COUNT + " INTEGER DEFAULT 0, " +
                COL_EVT_USER_RSVP + " TEXT DEFAULT 'NONE', " +
                COL_EVT_COUNTDOWN_ACTIVE + " INTEGER DEFAULT 1, " +
                COL_EVT_CREATED_AT + " INTEGER" +
                ");");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_CHANNEL_ADMIN_KEYS + " (" +
                COL_CAK_CHANNEL_ID + " TEXT PRIMARY KEY, " +
                COL_CAK_ADMIN_ALIAS + " TEXT, " +
                COL_CAK_BLIND_PUBLIC_KEY + " TEXT, " +
                COL_CAK_BLIND_PRIVATE_KEY + " TEXT, " +
                COL_CAK_ROTATION_EPOCH + " INTEGER DEFAULT 1, " +
                COL_CAK_LAST_ROTATED + " INTEGER" +
                ");");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_REPOSTS + " (" +
                COL_REP_ID + " TEXT PRIMARY KEY, " +
                COL_REP_ORIGINAL_POST_ID + " TEXT NOT NULL, " +
                COL_REP_ORIGINAL_AUTHOR_ID + " TEXT, " +
                COL_REP_ORIGINAL_AUTHOR_NAME + " TEXT, " +
                COL_REP_REPOSTER_PERSONA_ID + " TEXT, " +
                COL_REP_REPOSTER_NAME + " TEXT, " +
                COL_REP_QUOTE_TEXT + " TEXT, " +
                COL_REP_TIMESTAMP + " INTEGER" +
                ");");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_MODERATION_TRANSPARENCY_LOG + " (" +
                COL_MTL_LOG_ID + " TEXT PRIMARY KEY, " +
                COL_MTL_POST_ID + " TEXT NOT NULL, " +
                COL_MTL_REASON + " TEXT, " +
                COL_MTL_JUROR_COUNT + " INTEGER DEFAULT 1, " +
                COL_MTL_VERDICT + " TEXT, " +
                COL_MTL_CONTENT_HASH + " TEXT, " +
                COL_MTL_TIMESTAMP_MS + " INTEGER, " +
                COL_MTL_SIGNATURE + " TEXT" +
                ");");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_transparency_time ON " + TABLE_MODERATION_TRANSPARENCY_LOG + " (" + COL_MTL_TIMESTAMP_MS + " DESC);");

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sched_due ON " + TABLE_SCHEDULED_MESSAGES + " (" + COL_SCHED_STATUS + ", " + COL_SCHED_SCHEDULED_AT + " ASC);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rem_due ON " + TABLE_REMINDERS + " (" + COL_REM_STATUS + ", " + COL_REM_REMIND_AT + " ASC);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_subgroup_parent ON " + TABLE_GROUP_SUBGROUPS + " (" + COL_SUB_PARENT_GROUP_ID + ");");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_evt_group ON " + TABLE_GROUP_EVENTS + " (" + COL_EVT_GROUP_ID + ", " + COL_EVT_START_TIME + " ASC);");

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_status_author ON " +
                TABLE_STATUSES + " (" + COL_STATUS_AUTHOR_ID + ", " + COL_STATUS_TIMESTAMP_MS + " ASC);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_status_expires ON " +
                TABLE_STATUSES + " (" + COL_STATUS_EXPIRES_AT_MS + ");");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_status_views_status ON " +
                TABLE_STATUS_VIEWS + " (" + COL_SV_STATUS_ID + ");");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_status_gossip_target ON " +
                TABLE_STATUS_GOSSIP_QUEUE + " (" + COL_SGQ_TARGET_PEER_ID + ");");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.i(TAG, "Upgrading database from version " + oldVersion + " to " + newVersion);
        ensureSchema(db);
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        try {
            db.rawExecSQL("PRAGMA journal_mode = WAL;");
            db.rawExecSQL("PRAGMA synchronous = NORMAL;");
            db.rawExecSQL("PRAGMA temp_store = MEMORY;");
            db.rawExecSQL("PRAGMA secure_delete = ON;");
            db.rawExecSQL("PRAGMA journal_size_limit = 10485760;");
        } catch (Exception e) {
            Log.w(TAG, "Failed to apply PRAGMA optimizations: " + e.getMessage());
        }
        ensureSchema(db);
    }

    public synchronized void ensureSchema(SQLiteDatabase db) {
        if (db == null || db.isReadOnly()) return;
        try {
            onCreate(db);

            // Chats columns
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_NAME, "TEXT");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_LAST_MESSAGE, "TEXT");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_LAST_MESSAGE_TIME, "INTEGER DEFAULT 0");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_UNREAD_COUNT, "INTEGER DEFAULT 0");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_IS_GROUP, "INTEGER DEFAULT 0");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_AVATAR, "TEXT");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_TYPE, "TEXT");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_PARTICIPANTS, "TEXT");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_VANISH_TIMER, "INTEGER DEFAULT 0");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_VANISH_MODE, "TEXT");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_IS_BLOCKED, "INTEGER DEFAULT 0");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_BLOCKED_BY, "TEXT");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_PINNED, "INTEGER DEFAULT 0");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_MUTED, "INTEGER DEFAULT 0");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_ARCHIVED, "INTEGER DEFAULT 0");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_SECRET, "INTEGER DEFAULT 0");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_LOCK_PIN, "TEXT");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_WALLPAPER, "TEXT");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_SLOW_MODE, "INTEGER DEFAULT 0");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_GROUP_RULES, "TEXT");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_PINNED_MSG_ID, "TEXT");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_PINNED_MSG_TEXT, "TEXT");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_TRUST_SCORE, "INTEGER DEFAULT 10");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_GROUP_EXPIRES, "INTEGER DEFAULT 0");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_ADMIN_ID, "TEXT");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_ADMIN_IDS, "TEXT");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_DESCRIPTION, "TEXT");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_IS_PUBLIC, "INTEGER DEFAULT 0");
            ensureColumnExists(db, TABLE_CHATS, COL_CHAT_HANDLE, "TEXT");

            // Messages columns
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_CHAT_ID, "TEXT");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_SENDER_ID, "TEXT");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_RECEIVER_ID, "TEXT");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_TEXT, "TEXT");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_TYPE, "TEXT");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_TIMESTAMP, "INTEGER DEFAULT 0");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_READ, "INTEGER DEFAULT 0");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_DELIVERED, "INTEGER DEFAULT 0");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_PENDING, "INTEGER DEFAULT 0");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_LOCAL_PATH, "TEXT");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_MEDIA_URL, "TEXT");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_THUMBNAIL, "TEXT");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_EXPIRES_AT, "INTEGER DEFAULT 0");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_REPLY_ID, "TEXT");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_REPLY_NAME, "TEXT");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_REACTIONS, "TEXT");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_EDITED, "INTEGER DEFAULT 0");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_BLINK, "INTEGER DEFAULT 0");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_BLINK_VIEWED, "INTEGER DEFAULT 0");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_STARRED, "INTEGER DEFAULT 0");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_PINNED, "INTEGER DEFAULT 0");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_VOICE_FILTER, "TEXT");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_POLL_JSON, "TEXT");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_SENDER_NAME, "TEXT");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_VANISH_TIMER, "INTEGER DEFAULT 0");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_VANISH_MODE, "TEXT");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_VIEW_COUNT, "INTEGER DEFAULT 0");
            ensureColumnExists(db, TABLE_MESSAGES, COL_MSG_COMMENT_COUNT, "INTEGER DEFAULT 0");

            // Calls columns
            ensureColumnExists(db, TABLE_CALLS, COL_CALL_CALLER_ID, "TEXT");
            ensureColumnExists(db, TABLE_CALLS, COL_CALL_CALLER_NAME, "TEXT");
            ensureColumnExists(db, TABLE_CALLS, COL_CALL_RECEIVER_ID, "TEXT");
            ensureColumnExists(db, TABLE_CALLS, COL_CALL_RECEIVER_NAME, "TEXT");
            ensureColumnExists(db, TABLE_CALLS, COL_CALL_STATUS, "TEXT");
            ensureColumnExists(db, TABLE_CALLS, COL_CALL_IS_OUTGOING, "INTEGER DEFAULT 0");

            // Outbox & Mesh table safety check
            ensureColumnExists(db, TABLE_OUTBOX, COL_OUTBOX_ENVELOPE_ID, "TEXT");
            ensureColumnExists(db, TABLE_FILE_TRANSFERS, COL_FT_BITSET, "BLOB");
            ensureColumnExists(db, TABLE_MESH_ENVELOPES, COL_MESH_MAILBOX, "TEXT");

            // Self-heal any legacy calls with "Me" as the peer name
            try {
                db.execSQL("UPDATE " + TABLE_CALLS + " SET " + COL_CALL_PEER_NAME + " = " + COL_CALL_RECEIVER_NAME +
                        " WHERE (" + COL_CALL_PEER_NAME + " IN ('Me', 'me', 'You', 'you', 'local_user', 'null', 'Veil Peer') OR " +
                        COL_CALL_PEER_NAME + " IS NULL OR " + COL_CALL_PEER_NAME + " = '') AND " +
                        COL_CALL_RECEIVER_NAME + " IS NOT NULL AND " +
                        COL_CALL_RECEIVER_NAME + " NOT IN ('Me', 'me', 'You', 'you', 'local_user', 'null', '') ;");
                db.execSQL("UPDATE " + TABLE_CALLS + " SET " + COL_CALL_PEER_NAME + " = " + COL_CALL_CALLER_NAME +
                        " WHERE (" + COL_CALL_PEER_NAME + " IN ('Me', 'me', 'You', 'you', 'local_user', 'null', 'Veil Peer') OR " +
                        COL_CALL_PEER_NAME + " IS NULL OR " + COL_CALL_PEER_NAME + " = '') AND " +
                        COL_CALL_CALLER_NAME + " IS NOT NULL AND " +
                        COL_CALL_CALLER_NAME + " NOT IN ('Me', 'me', 'You', 'you', 'local_user', 'null', '') ;");
                db.execSQL("UPDATE " + TABLE_CALLS + " SET " + COL_CALL_PEER_NAME + " = 'Veil Contact' WHERE " +
                        COL_CALL_PEER_NAME + " IN ('Me', 'me', 'You', 'you', 'local_user', 'null') OR " +
                        COL_CALL_PEER_NAME + " IS NULL OR " + COL_CALL_PEER_NAME + " = '';");
                db.execSQL("UPDATE " + TABLE_CHATS + " SET " + COL_CHAT_NAME + " = 'Veil Contact' WHERE " +
                        COL_CHAT_NAME + " IN ('Me', 'me', 'You', 'you', 'local_user', 'null') OR " +
                        COL_CHAT_NAME + " IS NULL OR " + COL_CHAT_NAME + " = '';");
            } catch (Exception ignored) {}

            // Ensure P2P Status Reels Tables
            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_STATUSES + " (" +
                        COL_STATUS_ID + " TEXT PRIMARY KEY, " +
                        COL_STATUS_AUTHOR_ID + " TEXT, " +
                        COL_STATUS_AUTHOR_NAME + " TEXT, " +
                        COL_STATUS_CONTENT_TEXT + " TEXT, " +
                        COL_STATUS_MEDIA_TYPE + " TEXT, " +
                        COL_STATUS_MEDIA_FILE_PATH + " TEXT, " +
                        COL_STATUS_ENCRYPTED_FILE_PATH + " TEXT, " +
                        COL_STATUS_THUMB_BLOB + " BLOB, " +
                        COL_STATUS_BG_COLOR_HEX + " TEXT, " +
                        COL_STATUS_FONT_STYLE_INDEX + " INTEGER DEFAULT 0, " +
                        COL_STATUS_TIMESTAMP_MS + " INTEGER, " +
                        COL_STATUS_EXPIRES_AT_MS + " INTEGER, " +
                        COL_STATUS_TOTAL_BYTES + " INTEGER DEFAULT 0, " +
                        COL_STATUS_TOTAL_CHUNKS + " INTEGER DEFAULT 0, " +
                        COL_STATUS_PRIVACY_ACL_MODE + " INTEGER DEFAULT 0, " +
                        COL_STATUS_IS_MINE + " INTEGER DEFAULT 0, " +
                        COL_STATUS_IS_VIEWED + " INTEGER DEFAULT 0, " +
                        COL_STATUS_VIEW_COUNT + " INTEGER DEFAULT 0, " +
                        COL_STATUS_AES_KEY_WRAPPED + " TEXT, " +
                        COL_STATUS_IV_BASE64 + " TEXT" +
                        ");");

                db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_STATUS_VIEWS + " (" +
                        COL_SV_VIEW_ID + " TEXT PRIMARY KEY, " +
                        COL_SV_STATUS_ID + " TEXT, " +
                        COL_SV_VIEWER_ID + " TEXT, " +
                        COL_SV_VIEWER_NAME + " TEXT, " +
                        COL_SV_VIEWER_AVATAR_URL + " TEXT, " +
                        COL_SV_VIEWED_AT_MS + " INTEGER, " +
                        COL_SV_SIGNED_RECEIPT_BLOB + " BLOB" +
                        ");");

                db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_STATUS_CHUNKS + " (" +
                        COL_SC_STATUS_ID + " TEXT PRIMARY KEY, " +
                        COL_SC_CHUNK_BITMAP_BLOB + " BLOB, " +
                        COL_SC_COMPLETED_CHUNKS + " INTEGER DEFAULT 0, " +
                        COL_SC_TOTAL_CHUNKS + " INTEGER DEFAULT 0, " +
                        COL_SC_UPDATED_AT_MS + " INTEGER" +
                        ");");

                db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_STATUS_GOSSIP_QUEUE + " (" +
                        COL_SGQ_QUEUE_ID + " TEXT PRIMARY KEY, " +
                        COL_SGQ_STATUS_ID + " TEXT, " +
                        COL_SGQ_TARGET_PEER_ID + " TEXT, " +
                        COL_SGQ_PAYLOAD_BLOB + " BLOB, " +
                        COL_SGQ_RETRY_COUNT + " INTEGER DEFAULT 0, " +
                        COL_SGQ_LAST_ATTEMPT_MS + " INTEGER DEFAULT 0" +
                        ");");

                db.execSQL("CREATE INDEX IF NOT EXISTS idx_status_author ON " +
                        TABLE_STATUSES + " (" + COL_STATUS_AUTHOR_ID + ", " + COL_STATUS_TIMESTAMP_MS + " ASC);");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_status_expires ON " +
                        TABLE_STATUSES + " (" + COL_STATUS_EXPIRES_AT_MS + ");");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_status_views_status ON " +
                        TABLE_STATUS_VIEWS + " (" + COL_SV_STATUS_ID + ");");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_status_gossip_target ON " +
                        TABLE_STATUS_GOSSIP_QUEUE + " (" + COL_SGQ_TARGET_PEER_ID + ");");
            } catch (Exception ignored) {}

            // Ensure Moderation Transparency Log Table (#194, #196)
            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_MODERATION_TRANSPARENCY_LOG + " (" +
                        COL_MTL_LOG_ID + " TEXT PRIMARY KEY, " +
                        COL_MTL_POST_ID + " TEXT NOT NULL, " +
                        COL_MTL_REASON + " TEXT, " +
                        COL_MTL_JUROR_COUNT + " INTEGER DEFAULT 1, " +
                        COL_MTL_VERDICT + " TEXT, " +
                        COL_MTL_CONTENT_HASH + " TEXT, " +
                        COL_MTL_TIMESTAMP_MS + " INTEGER, " +
                        COL_MTL_SIGNATURE + " TEXT" +
                        ");");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_transparency_time ON " + TABLE_MODERATION_TRANSPARENCY_LOG + " (" + COL_MTL_TIMESTAMP_MS + " DESC);");
            } catch (Exception ignored) {}
        } catch (Exception e) {
            Log.e(TAG, "Error checking/updating database schema", e);
        }
    }

    private void ensureColumnExists(SQLiteDatabase db, String table, String column, String definition) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null);
            if (cursor != null) {
                int nameIndex = cursor.getColumnIndex("name");
                if (nameIndex != -1) {
                    while (cursor.moveToNext()) {
                        String existingCol = cursor.getString(nameIndex);
                        if (column.equalsIgnoreCase(existingCol)) {
                            return;
                        }
                    }
                }
            }
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (Exception e) {
            Log.w(TAG, "Notice while ensuring column " + column + " on " + table + ": " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    // ==========================================
    // CHAT OPERATIONS
    // ==========================================

    public synchronized void saveOrUpdateChat(Chat chat) {
        if (chat == null || chat.getId() == null) return;
        SQLiteDatabase db = getEncryptedDb();

        ContentValues cv = new ContentValues();
        cv.put(COL_CHAT_ID, chat.getId());
        cv.put(COL_CHAT_NAME, chat.getName());
        cv.put(COL_CHAT_LAST_MESSAGE, chat.getLastMessage());
        cv.put(COL_CHAT_LAST_MESSAGE_TIME, chat.getLastMessageTime() != null ? chat.getLastMessageTime().toDate().getTime() : System.currentTimeMillis());
        cv.put(COL_CHAT_UNREAD_COUNT, chat.getUnreadCount());
        cv.put(COL_CHAT_IS_GROUP, chat.isGroup() ? 1 : 0);
        cv.put(COL_CHAT_AVATAR, chat.getAvatar());
        cv.put(COL_CHAT_TYPE, chat.getType() != null ? chat.getType() : "direct");
        cv.put(COL_CHAT_VANISH_TIMER, chat.getVanishTimer());
        cv.put(COL_CHAT_VANISH_MODE, chat.getVanishMode() != null ? chat.getVanishMode() : "off");
        cv.put(COL_CHAT_IS_BLOCKED, chat.isBlocked() ? 1 : 0);
        cv.put(COL_CHAT_BLOCKED_BY, chat.getBlockedBy());
        cv.put(COL_CHAT_PINNED, chat.isPinned() ? 1 : 0);
        cv.put(COL_CHAT_MUTED, chat.isMuted() ? 1 : 0);
        cv.put(COL_CHAT_ARCHIVED, chat.isArchived() ? 1 : 0);
        cv.put(COL_CHAT_SECRET, chat.isSecret() ? 1 : 0);
        cv.put(COL_CHAT_LOCK_PIN, chat.getChatLockPin());
        cv.put(COL_CHAT_WALLPAPER, chat.getWallpaperColor());
        cv.put(COL_CHAT_SLOW_MODE, chat.getSlowModeSec());
        cv.put(COL_CHAT_GROUP_RULES, chat.getGroupRules());
        cv.put(COL_CHAT_PINNED_MSG_ID, chat.getPinnedMessageId());
        cv.put(COL_CHAT_PINNED_MSG_TEXT, chat.getPinnedMessageText());
        cv.put(COL_CHAT_TRUST_SCORE, Math.max(10, chat.getTrustScore()));
        cv.put(COL_CHAT_GROUP_EXPIRES, chat.getGroupExpiresAt());
        cv.put(COL_CHAT_ADMIN_ID, chat.getAdminId());
        if (chat.getAdminIds() != null && !chat.getAdminIds().isEmpty()) {
            StringBuilder asb = new StringBuilder();
            for (int i = 0; i < chat.getAdminIds().size(); i++) {
                if (i > 0) asb.append(",");
                asb.append(chat.getAdminIds().get(i));
            }
            cv.put(COL_CHAT_ADMIN_IDS, asb.toString());
        } else {
            cv.put(COL_CHAT_ADMIN_IDS, "");
        }
        cv.put(COL_CHAT_DESCRIPTION, chat.getDescription());
        cv.put(COL_CHAT_IS_PUBLIC, chat.isPublic() ? 1 : 0);
        cv.put(COL_CHAT_HANDLE, chat.getHandle());

        if (chat.getParticipants() != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < chat.getParticipants().size(); i++) {
                sb.append(chat.getParticipants().get(i));
                if (i < chat.getParticipants().size() - 1) sb.append(",");
            }
            cv.put(COL_CHAT_PARTICIPANTS, sb.toString());
        }

        try {
            db.insertWithOnConflict(TABLE_CHATS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            ensureSchema(db);
            try {
                db.insertWithOnConflict(TABLE_CHATS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            } catch (Exception retryEx) {
                Log.e(TAG, "saveOrUpdateChat retry failed", retryEx);
            }
        }
        ramChatCache.put(chat.getId(), chat);
        notifyChatsChanged();
    }

    public synchronized List<Chat> getAllChats() {
        return getAllChats(false);
    }

    /**
     * Lock-free 0ms RAM cache lookup for a Chat dialog (modeled after Telegram MessagesController.getDialog).
     * Never blocks on SQLCipher monitor locks so UI thread can read instantaneously during Activity startup.
     */
    public Chat getCachedChatImmediate(String chatId) {
        if (chatId == null) return null;
        return ramChatCache.get(chatId);
    }

    /**
     * Lock-free 0ms RAM cache lookup for a dialog's messages list.
     * Returns a defensive copy if present in RAM, or null if not yet loaded.
     */
    public List<Message> getCachedMessagesImmediate(String chatId) {
        if (chatId == null) return null;
        List<Message> cached = ramMessageListCache.get(chatId);
        return cached != null ? new ArrayList<>(cached) : null;
    }

    public synchronized List<Chat> getAllChats(boolean includeSecret) {
        deleteExpiredVanishMessages();
        List<Chat> chats = new ArrayList<>();
        SQLiteDatabase db = getEncryptedDb();
        long now = System.currentTimeMillis();

        Cursor cursor = db.query(TABLE_CHATS, null, null, null, null, null,
                COL_CHAT_LAST_MESSAGE_TIME + " DESC");

        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    Chat chat = cursorToChat(cursor);
                    if (chat != null) {
                        if (chat.getGroupExpiresAt() > 0 && chat.getGroupExpiresAt() <= now) {
                            deleteChat(chat.getId());
                            continue;
                        }
                        if (chat.getId() != null) {
                            ramChatCache.put(chat.getId(), chat);
                        }
                        if (includeSecret || !chat.isSecret()) {
                            chats.add(chat);
                        }
                    }
                }
            } finally {
                cursor.close();
            }
        }
        return chats;
    }

    public synchronized List<Chat> getSecretChats() {
        deleteExpiredVanishMessages();
        List<Chat> chats = new ArrayList<>();
        SQLiteDatabase db = getEncryptedDb();
        long now = System.currentTimeMillis();

        Cursor cursor = db.query(TABLE_CHATS, null, COL_CHAT_SECRET + "=1 AND " + COL_CHAT_ARCHIVED + "=0", null, null, null,
                COL_CHAT_LAST_MESSAGE_TIME + " DESC");

        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    Chat chat = cursorToChat(cursor);
                    if (chat != null) {
                        if (chat.getGroupExpiresAt() > 0 && chat.getGroupExpiresAt() <= now) {
                            deleteChat(chat.getId());
                            continue;
                        }
                        chats.add(chat);
                    }
                }
            } finally {
                cursor.close();
            }
        }
        return chats;
    }

    public synchronized int getSecretChatsCount() {
        SQLiteDatabase db = getEncryptedDb();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_CHATS + " WHERE " + COL_CHAT_SECRET + " = 1 AND " + COL_CHAT_ARCHIVED + " = 0", null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return 0;
    }

    public synchronized List<Chat> getBlockedChats() {
        List<Chat> chats = new ArrayList<>();
        SQLiteDatabase db = getEncryptedDb();
        Cursor cursor = db.query(TABLE_CHATS, null, COL_CHAT_IS_BLOCKED + " = 1", null, null, null,
                COL_CHAT_LAST_MESSAGE_TIME + " DESC");
        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    Chat c = cursorToChat(cursor);
                    if (c != null) chats.add(c);
                }
            } finally {
                cursor.close();
            }
        }
        return chats;
    }

    public synchronized int getBlockedChatsCount() {
        SQLiteDatabase db = getEncryptedDb();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_CHATS + " WHERE " + COL_CHAT_IS_BLOCKED + " = 1", null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return 0;
    }

    public synchronized void setChatBlocked(String chatId, boolean blocked, String blockedBy) {
        if (chatId == null) return;
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(COL_CHAT_IS_BLOCKED, blocked ? 1 : 0);
        cv.put(COL_CHAT_BLOCKED_BY, blocked ? blockedBy : null);
        db.update(TABLE_CHATS, cv, COL_CHAT_ID + " = ?", new String[]{chatId});
        Chat cached = ramChatCache.get(chatId);
        if (cached != null) {
            cached.setBlocked(blocked);
            cached.setBlockedBy(blocked ? blockedBy : null);
        }
        notifyChatsChanged();
    }

    public synchronized Chat getChat(String chatId) {
        if (chatId == null) return null;
        Chat cached = ramChatCache.get(chatId);
        if (cached != null) return cached;

        SQLiteDatabase db = getEncryptedDb();

        Cursor cursor = db.query(TABLE_CHATS, null, COL_CHAT_ID + "=?",
                new String[]{chatId}, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    Chat c = cursorToChat(cursor);
                    if (c != null) ramChatCache.put(chatId, c);
                    return c;
                }
            } finally {
                cursor.close();
            }
        }
        return null;
    }

    private Chat cursorToChat(Cursor cursor) {
        String id = cursor.getString(cursor.getColumnIndexOrThrow(COL_CHAT_ID));
        String name = cursor.getString(cursor.getColumnIndexOrThrow(COL_CHAT_NAME));
        String lastMessage = cursor.getString(cursor.getColumnIndexOrThrow(COL_CHAT_LAST_MESSAGE));
        long lastTimeMillis = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CHAT_LAST_MESSAGE_TIME));
        int unreadCount = cursor.getInt(cursor.getColumnIndexOrThrow(COL_CHAT_UNREAD_COUNT));
        boolean isGroup = cursor.getInt(cursor.getColumnIndexOrThrow(COL_CHAT_IS_GROUP)) == 1;
        String avatar = cursor.getString(cursor.getColumnIndexOrThrow(COL_CHAT_AVATAR));
        String type = cursor.getString(cursor.getColumnIndexOrThrow(COL_CHAT_TYPE));
        long vanishTimer = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CHAT_VANISH_TIMER));
        String vanishMode = cursor.getString(cursor.getColumnIndexOrThrow(COL_CHAT_VANISH_MODE));
        boolean isBlocked = cursor.getInt(cursor.getColumnIndexOrThrow(COL_CHAT_IS_BLOCKED)) == 1;
        String blockedBy = cursor.getString(cursor.getColumnIndexOrThrow(COL_CHAT_BLOCKED_BY));

        String participantsStr = cursor.getString(cursor.getColumnIndexOrThrow(COL_CHAT_PARTICIPANTS));
        List<String> participants = new ArrayList<>();
        if (participantsStr != null && !participantsStr.isEmpty()) {
            Collections.addAll(participants, participantsStr.split(","));
        }

        Timestamp lastMsgTime = lastTimeMillis > 0 ? new Timestamp(new Date(lastTimeMillis)) : null;

        Chat chat = new Chat(id, name, lastMessage, lastMsgTime, avatar, unreadCount, isGroup, false, type, participants);
        chat.setVanishTimer(vanishTimer);
        chat.setVanishMode(vanishMode);
        chat.setBlocked(isBlocked);
        chat.setBlockedBy(blockedBy);

        int pinnedIdx = cursor.getColumnIndex(COL_CHAT_PINNED);
        if (pinnedIdx != -1) chat.setPinned(cursor.getInt(pinnedIdx) == 1);
        int mutedIdx = cursor.getColumnIndex(COL_CHAT_MUTED);
        if (mutedIdx != -1) chat.setMuted(cursor.getInt(mutedIdx) == 1);
        int archivedIdx = cursor.getColumnIndex(COL_CHAT_ARCHIVED);
        if (archivedIdx != -1) chat.setArchived(cursor.getInt(archivedIdx) == 1);
        int secretIdx = cursor.getColumnIndex(COL_CHAT_SECRET);
        if (secretIdx != -1) chat.setSecret(cursor.getInt(secretIdx) == 1);
        int lockPinIdx = cursor.getColumnIndex(COL_CHAT_LOCK_PIN);
        if (lockPinIdx != -1) chat.setChatLockPin(cursor.getString(lockPinIdx));
        int wallpaperIdx = cursor.getColumnIndex(COL_CHAT_WALLPAPER);
        if (wallpaperIdx != -1) chat.setWallpaperColor(cursor.getString(wallpaperIdx));
        int slowIdx = cursor.getColumnIndex(COL_CHAT_SLOW_MODE);
        if (slowIdx != -1) chat.setSlowModeSec(cursor.getInt(slowIdx));
        int rulesIdx = cursor.getColumnIndex(COL_CHAT_GROUP_RULES);
        if (rulesIdx != -1) chat.setGroupRules(cursor.getString(rulesIdx));
        int pinMsgIdIdx = cursor.getColumnIndex(COL_CHAT_PINNED_MSG_ID);
        if (pinMsgIdIdx != -1) chat.setPinnedMessageId(cursor.getString(pinMsgIdIdx));
        int pinMsgTxtIdx = cursor.getColumnIndex(COL_CHAT_PINNED_MSG_TEXT);
        if (pinMsgTxtIdx != -1) chat.setPinnedMessageText(cursor.getString(pinMsgTxtIdx));
        int trustIdx = cursor.getColumnIndex(COL_CHAT_TRUST_SCORE);
        if (trustIdx != -1) chat.setTrustScore(cursor.getInt(trustIdx));
        int expIdx = cursor.getColumnIndex(COL_CHAT_GROUP_EXPIRES);
        if (expIdx != -1) chat.setGroupExpiresAt(cursor.getLong(expIdx));
        int adminIdIdx = cursor.getColumnIndex(COL_CHAT_ADMIN_ID);
        if (adminIdIdx != -1) chat.setAdminId(cursor.getString(adminIdIdx));
        int adminIdsIdx = cursor.getColumnIndex(COL_CHAT_ADMIN_IDS);
        if (adminIdsIdx != -1) {
            String aStr = cursor.getString(adminIdsIdx);
            if (aStr != null && !aStr.isEmpty()) {
                List<String> aList = new ArrayList<>();
                Collections.addAll(aList, aStr.split(","));
                chat.setAdminIds(aList);
            }
        }
        int descIdx = cursor.getColumnIndex(COL_CHAT_DESCRIPTION);
        if (descIdx != -1) chat.setDescription(cursor.getString(descIdx));
        int pubIdx = cursor.getColumnIndex(COL_CHAT_IS_PUBLIC);
        if (pubIdx != -1) chat.setPublic(cursor.getInt(pubIdx) == 1);
        int handleIdx = cursor.getColumnIndex(COL_CHAT_HANDLE);
        if (handleIdx != -1) chat.setHandle(cursor.getString(handleIdx));

        // Populate last message status & type for WhatsApp-style chat list ticks
        populateLastMessageMetadata(chat);

        return chat;
    }

    private void populateLastMessageMetadata(Chat chat) {
        if (chat == null || chat.getId() == null) return;
        Cursor msgCursor = null;
        try {
            SQLiteDatabase db = getEncryptedDb();
            long now = System.currentTimeMillis();
            // Filter out any messages that have expired (vanished)
            String selection = COL_MSG_CHAT_ID + "=? AND (" + COL_MSG_EXPIRES_AT + "=0 OR " + COL_MSG_EXPIRES_AT + ">?)";
            String[] selectionArgs = new String[]{chat.getId(), String.valueOf(now)};

            msgCursor = db.query(TABLE_MESSAGES,
                    new String[]{COL_MSG_SENDER_ID, COL_MSG_TEXT, COL_MSG_TYPE, COL_MSG_TIMESTAMP, COL_MSG_DELIVERED, COL_MSG_READ, COL_MSG_PENDING, COL_MSG_BLINK},
                    selection,
                    selectionArgs,
                    null, null,
                    COL_MSG_TIMESTAMP + " DESC",
                    "1");

            if (msgCursor != null && msgCursor.moveToFirst()) {
                String senderId = msgCursor.getString(0);
                String text = msgCursor.getString(1);
                String type = msgCursor.getString(2);
                long timestamp = msgCursor.getLong(3);
                boolean delivered = msgCursor.getInt(4) == 1;
                boolean read = msgCursor.getInt(5) == 1;
                boolean pending = msgCursor.getInt(6) == 1;
                boolean isBlink = msgCursor.getInt(7) == 1;

                String summary;
                if (isBlink) {
                    summary = "⚡ Blink (View-Once)";
                } else if ("poll".equals(type)) {
                    summary = "📊 Poll: " + (text != null ? text : "");
                } else if ("call".equals(type)) {
                    boolean isVideo = text != null && text.toLowerCase().contains("video");
                    String icon = isVideo ? "📹 " : "📞 ";
                    summary = icon + (text != null ? text : "Call");
                } else if ("deleted".equals(type)) {
                    summary = "🚫 This message was deleted";
                } else if (type != null && !type.equals("text")) {
                    summary = (text != null && !text.isEmpty()) ? text : "[" + type + "]";
                } else {
                    summary = text != null ? text : "";
                }

                String prevLastMsg = chat.getLastMessage();
                long prevLastTime = chat.getLastMessageTime() != null ? chat.getLastMessageTime().toDate().getTime() : 0L;

                chat.setLastMessage(summary);
                if (timestamp > 0) {
                    chat.setLastMessageTime(new Timestamp(new Date(timestamp)));
                }
                chat.setLastMessageSenderId(senderId);
                chat.setLastMessageType(isBlink ? "blink" : type);
                chat.setLastMessageDelivered(delivered);
                chat.setLastMessageRead(read);
                chat.setLastMessagePending(pending);

                // Only write to TABLE_CHATS when the summary or timestamp actually changed
                if (!summary.equals(prevLastMsg) || (timestamp > 0 && timestamp != prevLastTime)) {
                    ContentValues cv = new ContentValues();
                    cv.put(COL_CHAT_LAST_MESSAGE, summary);
                    if (timestamp > 0) cv.put(COL_CHAT_LAST_MESSAGE_TIME, timestamp);
                    db.update(TABLE_CHATS, cv, COL_CHAT_ID + "=?", new String[]{chat.getId()});
                }
            } else {
                String prevLastMsg = chat.getLastMessage();
                // No surviving messages in this chat (all messages expired or chat is empty)
                chat.setLastMessage("");
                chat.setLastMessageSenderId(null);
                chat.setLastMessageType(null);
                chat.setLastMessageDelivered(false);
                chat.setLastMessageRead(false);
                chat.setLastMessagePending(false);

                if (prevLastMsg != null && !prevLastMsg.isEmpty()) {
                    ContentValues cv = new ContentValues();
                    cv.put(COL_CHAT_LAST_MESSAGE, "");
                    db.update(TABLE_CHATS, cv, COL_CHAT_ID + "=?", new String[]{chat.getId()});
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (msgCursor != null) msgCursor.close();
        }
    }

    public synchronized void updateChatUnreadCount(String chatId, int unreadCount) {
        int clamped = Math.max(0, unreadCount);
        Chat cached = ramChatCache.get(chatId);
        if (cached != null) {
            cached.setUnreadCount(clamped);
        }
        updateSingleChatColumn(chatId, COL_CHAT_UNREAD_COUNT, clamped);
    }

    public synchronized void incrementUnreadCount(String chatId) {
        if (chatId == null) return;
        Chat chat = getChat(chatId);
        int current = chat != null ? chat.getUnreadCount() : 0;
        updateChatUnreadCount(chatId, current + 1);
    }

    public synchronized void deleteMessage(String messageId) {
        deleteSingleMessage(messageId);
    }

    public synchronized void markMessagesAsRead(String chatId) {
        String uid = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this.context);
        if (uid == null || uid.trim().isEmpty()) {
            uid = this.context.getSharedPreferences("veil_prefs", Context.MODE_PRIVATE).getString("user_id", null);
        }
        if (uid == null || uid.trim().isEmpty()) {
            uid = com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(this.context).getCurrentUserId();
        }
        if (uid == null) uid = "";
        markAllAsRead(chatId, uid);
    }

    public synchronized void saveGroupMembers(String groupId, List<String> members, String currentUserId) {
        if (groupId == null || members == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < members.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(members.get(i));
        }
        updateSingleChatColumn(groupId, COL_CHAT_PARTICIPANTS, sb.toString());
    }

    public synchronized void deleteChat(String chatId) {
        if (chatId == null) return;
        ramChatCache.remove(chatId);
        ramMessageListCache.remove(chatId);
        try {
            com.m3.veil.manager.VeilMessagesController.getInstance(context).removeDialogFromCache(chatId);
        } catch (Throwable ignored) {}
        SQLiteDatabase db = getEncryptedDb();
        db.delete(TABLE_MESSAGES, COL_MSG_CHAT_ID + "=?", new String[]{chatId});
        db.delete(TABLE_CHATS, COL_CHAT_ID + "=?", new String[]{chatId});
        notifyMessagesChanged(chatId);
        notifyChatsChanged();
    }

    public synchronized void updateChatPinStatus(String chatId, boolean pinned) {
        Chat cached = ramChatCache.get(chatId);
        if (cached != null) cached.setPinned(pinned);
        updateSingleChatColumn(chatId, COL_CHAT_PINNED, pinned ? 1 : 0);
    }

    public synchronized void updateChatMuteStatus(String chatId, boolean muted) {
        Chat cached = ramChatCache.get(chatId);
        if (cached != null) cached.setMuted(muted);
        updateSingleChatColumn(chatId, COL_CHAT_MUTED, muted ? 1 : 0);
    }

    public synchronized void updateChatArchiveStatus(String chatId, boolean archived) {
        Chat cached = ramChatCache.get(chatId);
        if (cached != null) cached.setArchived(archived);
        updateSingleChatColumn(chatId, COL_CHAT_ARCHIVED, archived ? 1 : 0);
    }

    public synchronized void updateChatSecretStatus(String chatId, boolean secret) {
        Chat cached = ramChatCache.get(chatId);
        if (cached != null) cached.setSecret(secret);
        updateSingleChatColumn(chatId, COL_CHAT_SECRET, secret ? 1 : 0);
    }

    public synchronized void updateChatLockPin(String chatId, String pin) {
        if (chatId == null) return;
        Chat cached = ramChatCache.get(chatId);
        if (cached != null) cached.setChatLockPin(pin);
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(COL_CHAT_LOCK_PIN, pin);
        db.update(TABLE_CHATS, cv, COL_CHAT_ID + "=?", new String[]{chatId});
        notifyChatsChanged();
    }

    public synchronized void updateChatWallpaper(String chatId, String colorHex) {
        if (chatId == null) return;
        String normalized = (colorHex == null || colorHex.trim().isEmpty() || "default".equalsIgnoreCase(colorHex.trim())) ? null : colorHex.trim();
        Chat cached = ramChatCache.get(chatId);
        if (cached != null) cached.setWallpaperColor(normalized);
        try {
            Chat ctrlCached = com.m3.veil.manager.VeilMessagesController.getInstance(context).getCachedDialogImmediate(chatId);
            if (ctrlCached != null) ctrlCached.setWallpaperColor(normalized);
        } catch (Throwable ignored) {}
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(COL_CHAT_WALLPAPER, normalized);
        db.update(TABLE_CHATS, cv, COL_CHAT_ID + "=?", new String[]{chatId});
        notifyChatsChanged();
    }

    public synchronized void resetAllChatWallpapersToThemeDefault() {
        for (Chat c : ramChatCache.values()) {
            if (c != null) {
                String wp = c.getWallpaperColor();
                if (wp != null && !wp.startsWith("file:") && !wp.startsWith("content:") && !wp.startsWith("/")) {
                    c.setWallpaperColor(null);
                }
            }
        }
        try {
            SQLiteDatabase db = getEncryptedDb();
            ContentValues cv = new ContentValues();
            cv.putNull(COL_CHAT_WALLPAPER);
            db.update(TABLE_CHATS, cv,
                    COL_CHAT_WALLPAPER + " IS NOT NULL AND "
                            + COL_CHAT_WALLPAPER + " NOT LIKE 'file:%' AND "
                            + COL_CHAT_WALLPAPER + " NOT LIKE 'content:%' AND "
                            + COL_CHAT_WALLPAPER + " NOT LIKE '/%'",
                    null);
        } catch (Exception ignored) {}
        notifyChatsChanged();
    }

    public synchronized String getChatWallpaper(String chatId) {
        if (chatId == null) return null;
        Chat cached = ramChatCache.get(chatId);
        if (cached != null && cached.getWallpaperColor() != null && !cached.getWallpaperColor().isEmpty()) {
            return cached.getWallpaperColor();
        }
        SQLiteDatabase db = getEncryptedDb();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_CHATS, new String[]{COL_CHAT_WALLPAPER}, COL_CHAT_ID + "=?", new String[]{chatId}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String wp = cursor.getString(0);
                if (cached != null) {
                    cached.setWallpaperColor(wp);
                }
                return wp;
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    public synchronized void updateChatPinnedMessage(String chatId, String msgId, String msgText) {
        if (chatId == null) return;
        Chat cached = ramChatCache.get(chatId);
        if (cached != null) {
            cached.setPinnedMessageId(msgId);
            cached.setPinnedMessageText(msgText);
        }
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(COL_CHAT_PINNED_MSG_ID, msgId);
        cv.put(COL_CHAT_PINNED_MSG_TEXT, msgText);
        db.update(TABLE_CHATS, cv, COL_CHAT_ID + "=?", new String[]{chatId});
        notifyChatsChanged();
    }

    public synchronized void incrementChatTrustScore(String chatId, int delta) {
        Chat chat = getChat(chatId);
        if (chat != null) {
            int next = Math.min(100, Math.max(0, chat.getTrustScore() + delta));
            updateSingleChatColumn(chatId, COL_CHAT_TRUST_SCORE, next);
        }
    }

    private void updateSingleChatColumn(String chatId, String col, int val) {
        if (chatId == null) return;
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(col, val);
        try {
            db.update(TABLE_CHATS, cv, COL_CHAT_ID + "=?", new String[]{chatId});
        } catch (Exception e) {
            ensureSchema(db);
            try {
                db.update(TABLE_CHATS, cv, COL_CHAT_ID + "=?", new String[]{chatId});
            } catch (Exception ignored) {}
        }
        notifyChatsChanged();
    }

    private void updateSingleChatColumn(String chatId, String col, String val) {
        if (chatId == null) return;
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(col, val);
        try {
            db.update(TABLE_CHATS, cv, COL_CHAT_ID + "=?", new String[]{chatId});
        } catch (Exception e) {
            ensureSchema(db);
            try {
                db.update(TABLE_CHATS, cv, COL_CHAT_ID + "=?", new String[]{chatId});
            } catch (Exception ignored) {}
        }
        notifyChatsChanged();
    }

    public synchronized List<Chat> getArchivedChats() {
        deleteExpiredVanishMessages();
        List<Chat> chats = new ArrayList<>();
        SQLiteDatabase db = getEncryptedDb();
        Cursor cursor = db.query(TABLE_CHATS, null, COL_CHAT_ARCHIVED + "=1", null, null, null,
                COL_CHAT_LAST_MESSAGE_TIME + " DESC");
        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    Chat chat = cursorToChat(cursor);
                    if (chat != null) chats.add(chat);
                }
            } finally {
                cursor.close();
            }
        }
        return chats;
    }

    public synchronized int getArchivedChatsCount() {
        SQLiteDatabase db = getEncryptedDb();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_CHATS + " WHERE " + COL_CHAT_ARCHIVED + "=1", null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error counting archived chats: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return 0;
    }

    // ==========================================
    // MESSAGE OPERATIONS
    // ==========================================

    public synchronized void saveMessage(String chatId, Message message) {
        if (message == null || message.getId() == null || chatId == null) return;
        SQLiteDatabase db = getEncryptedDb();

        ContentValues cv = new ContentValues();
        cv.put(COL_MSG_ID, message.getId());
        cv.put(COL_MSG_CHAT_ID, chatId);
        cv.put(COL_MSG_SENDER_ID, message.getSenderId());
        cv.put(COL_MSG_RECEIVER_ID, message.getReceiverId());
        cv.put(COL_MSG_TEXT, message.getText());
        cv.put(COL_MSG_TYPE, message.getType() != null ? message.getType() : "text");
        cv.put(COL_MSG_TIMESTAMP, message.getTimestamp() != null ? message.getTimestamp().toDate().getTime() : System.currentTimeMillis());
        cv.put(COL_MSG_READ, message.isRead() ? 1 : 0);
        cv.put(COL_MSG_DELIVERED, message.isDelivered() ? 1 : 0);
        cv.put(COL_MSG_PENDING, message.isPending() ? 1 : 0);
        String localPath = message.getLocalFilePath();
        String mediaUrlToStore = message.getMediaUrl();
        if (mediaUrlToStore != null && mediaUrlToStore.startsWith("INLINE_B64:")
                && !message.isPending() && localPath != null && !localPath.isEmpty()) {
            File localFile = new File(localPath);
            if (localFile.exists() && localFile.length() > 0) {
                mediaUrlToStore = "LOCAL_DECODED";
                message.setMediaUrl(mediaUrlToStore);
            }
        }
        cv.put(COL_MSG_LOCAL_PATH, localPath);
        cv.put(COL_MSG_MEDIA_URL, mediaUrlToStore);
        cv.put(COL_MSG_THUMBNAIL, message.getThumbnail());
        cv.put(COL_MSG_EXPIRES_AT, message.getExpiresAt() != null ? message.getExpiresAt().toDate().getTime() : 0);
        cv.put(COL_MSG_REPLY_ID, message.getReplyToMessageId());
        cv.put(COL_MSG_REPLY_NAME, message.getReplySenderName());
        cv.put(COL_MSG_EDITED, message.isEdited() ? 1 : 0);
        cv.put(COL_MSG_BLINK, message.isBlink() ? 1 : 0);
        cv.put(COL_MSG_BLINK_VIEWED, message.isBlinkViewed() ? 1 : 0);
        cv.put(COL_MSG_STARRED, message.isStarred() ? 1 : 0);
        cv.put(COL_MSG_PINNED, message.isPinned() ? 1 : 0);
        cv.put(COL_MSG_VOICE_FILTER, message.getVoiceFilter());
        cv.put(COL_MSG_POLL_JSON, message.getPollJson());
        cv.put(COL_MSG_SENDER_NAME, message.getSenderName());
        cv.put(COL_MSG_VANISH_TIMER, message.getVanishTimer());
        cv.put(COL_MSG_VANISH_MODE, message.getVanishMode());
        cv.put(COL_MSG_VIEW_COUNT, message.getViewCount());
        cv.put(COL_MSG_COMMENT_COUNT, message.getCommentCount());
        message.setChatId(chatId);

        if (message.getReactions() != null && !message.getReactions().isEmpty()) {
            try {
                JSONObject rObj = new JSONObject();
                for (Map.Entry<String, String> entry : message.getReactions().entrySet()) {
                    rObj.put(entry.getKey(), entry.getValue());
                }
                cv.put(COL_MSG_REACTIONS, rObj.toString());
            } catch (Exception ignored) {}
        } else {
            cv.put(COL_MSG_REACTIONS, "");
        }

        try {
            db.insertWithOnConflict(TABLE_MESSAGES, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            ensureSchema(db);
            try {
                db.insertWithOnConflict(TABLE_MESSAGES, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            } catch (Exception retryEx) {
                Log.e(TAG, "saveMessage retry failed", retryEx);
            }
        }

        // Update parent chat summary
        ContentValues chatCv = new ContentValues();
        String summary;
        if (message.isBlink()) {
            summary = "⚡ Blink (View-Once)";
        } else if ("poll".equals(message.getType())) {
            summary = "📊 Poll: " + message.getText();
        } else if ("call".equals(message.getType())) {
            boolean isVideo = message.getText() != null && message.getText().toLowerCase().contains("video");
            String icon = isVideo ? "📹 " : "📞 ";
            summary = icon + (message.getText() != null ? message.getText() : "Call");
        } else if (message.getType() != null && !message.getType().equals("text")) {
            summary = "[" + message.getType() + "]";
        } else {
            summary = message.getText();
        }
        chatCv.put(COL_CHAT_LAST_MESSAGE, summary);
        chatCv.put(COL_CHAT_LAST_MESSAGE_TIME, cv.getAsLong(COL_MSG_TIMESTAMP));
        db.update(TABLE_CHATS, chatCv, COL_CHAT_ID + "=?", new String[]{chatId});

        ramSingleMessageCache.put(message.getId(), message);
        List<Message> cachedList = ramMessageListCache.get(chatId);
        if (cachedList != null) {
            boolean replaced = false;
            for (int i = 0; i < cachedList.size(); i++) {
                Message existing = cachedList.get(i);
                if (existing != null && message.getId().equals(existing.getId())) {
                    cachedList.set(i, message);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                cachedList.add(message);
            }
        }
        Chat cachedChat = ramChatCache.get(chatId);
        if (cachedChat != null) {
            populateLastMessageMetadata(cachedChat);
        }

        notifyMessagesChanged(chatId);
        notifyChatsChanged();
    }

    public synchronized void updateMessageReaction(String chatId, String messageId, String userId, String emoji) {
        if (chatId == null || messageId == null || userId == null) return;
        Message msg = getMessageById(messageId);
        if (msg == null) return;
        Map<String, String> reactions = msg.getReactions();
        if (reactions == null) reactions = new HashMap<>();
        if (emoji == null || emoji.isEmpty()) {
            reactions.remove(userId);
        } else {
            reactions.put(userId, emoji);
        }
        msg.setReactions(reactions);
        saveMessage(chatId, msg);
    }

    public synchronized void updateMessageText(String chatId, String messageId, String newText) {
        if (chatId == null || messageId == null) return;
        Message msg = getMessageById(messageId);
        if (msg == null) return;
        msg.setText(newText);
        msg.setEdited(true);
        saveMessage(chatId, msg);
    }

    public synchronized void markMessageDeletedForEveryone(String chatId, String messageId) {
        if (messageId == null) return;
        Message msg = getMessageById(messageId);
        if (msg == null) return;
        if (chatId == null) {
            Cursor c = getEncryptedDb().query(TABLE_MESSAGES, new String[]{COL_MSG_CHAT_ID}, COL_MSG_ID + "=?", new String[]{messageId}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) chatId = c.getString(0);
                } finally {
                    c.close();
                }
            }
        }
        if (chatId == null) return;
        msg.setType("deleted");
        msg.setText("🚫 This message was deleted");
        msg.setLocalFilePath(null);
        msg.setMediaUrl(null);
        msg.setThumbnail(null);
        msg.setPollJson(null);
        msg.setReactions(null);
        saveMessage(chatId, msg);
    }

    public synchronized void markMessageDeleted(String chatId, String messageId) {
        markMessageDeletedForEveryone(chatId, messageId);
    }

    public synchronized void updateMessagePollJson(String chatId, String messageId, String pollJson) {
        if (chatId == null || messageId == null) return;
        Message msg = getMessageById(messageId);
        if (msg == null) return;
        msg.setPollJson(pollJson);
        saveMessage(chatId, msg);
    }

    public synchronized void markBlinkMessageViewed(String chatId, String messageId) {
        if (chatId == null || messageId == null) return;
        Message msg = getMessageById(messageId);
        if (msg == null) return;
        msg.setBlinkViewed(true);
        msg.setText("⚡ Blink message opened");
        msg.setLocalFilePath(null);
        msg.setMediaUrl(null);
        saveMessage(chatId, msg);
    }

    public synchronized Message getMessage(String messageId) {
        return getMessageById(messageId);
    }

    public synchronized Message getMessageById(String messageId) {
        if (messageId == null) return null;
        Message cached = ramSingleMessageCache.get(messageId);
        if (cached != null) return cached;

        SQLiteDatabase db = getEncryptedDb();
        Cursor cursor = db.query(TABLE_MESSAGES, null, COL_MSG_ID + "=?",
                new String[]{messageId}, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    Message m = cursorToMessage(cursor);
                    if (m != null) ramSingleMessageCache.put(messageId, m);
                    return m;
                }
            } finally {
                cursor.close();
            }
        }
        return null;
    }

    public synchronized List<Message> getMessagesForChat(String chatId) {
        return getMessages(chatId);
    }

    public synchronized List<Message> getMessages(String chatId) {
        return getMessages(chatId, 200, 0);
    }

    public synchronized List<Message> getMessages(String chatId, int limit, int offset) {
        List<Message> messages = new ArrayList<>();
        if (chatId == null) return messages;
        if (offset == 0) {
            List<Message> cached = ramMessageListCache.get(chatId);
            if (cached != null && !cached.isEmpty()) {
                return new ArrayList<>(cached);
            }
        }
        SQLiteDatabase db = getEncryptedDb();

        String limitClause = offset > 0 ? offset + "," + limit : String.valueOf(limit);
        Cursor cursor = db.query(TABLE_MESSAGES, null, COL_MSG_CHAT_ID + "=?",
                new String[]{chatId}, null, null, COL_MSG_TIMESTAMP + " ASC", limitClause);

        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    Message m = cursorToMessage(cursor);
                    if (m != null) {
                        ramSingleMessageCache.put(m.getId(), m);
                        messages.add(m);
                    }
                }
            } finally {
                cursor.close();
            }
        }
        if (offset == 0 && limit >= 50) {
            ramMessageListCache.put(chatId, new CopyOnWriteArrayList<>(messages));
        }
        return messages;
    }

    public synchronized List<Message> searchMessages(String query, int limit) {
        List<Message> list = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return list;
        SQLiteDatabase db = getEncryptedDb();
        String escaped = query.replace("%", "\\%").replace("_", "\\_");
        Cursor cursor = db.rawQuery(
                "SELECT * FROM " + TABLE_MESSAGES +
                " WHERE " + COL_MSG_TEXT + " LIKE ? ESCAPE '\\' ORDER BY " + COL_MSG_TIMESTAMP + " DESC LIMIT ?",
                new String[]{"%" + escaped + "%", String.valueOf(limit > 0 ? limit : 50)}
        );
        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    Message m = cursorToMessage(cursor);
                    if (m != null) list.add(m);
                }
            } finally {
                cursor.close();
            }
        }
        return list;
    }

    public synchronized List<Message> getPendingMessages(String peerId) {
        List<Message> messages = new ArrayList<>();
        if (peerId == null) return messages;
        SQLiteDatabase db = getEncryptedDb();

        // Fetch all outbound messages to this peer that have not yet been delivered over P2P DataChannel
        Cursor cursor = db.query(TABLE_MESSAGES, null,
                COL_MSG_RECEIVER_ID + "=? AND " + COL_MSG_DELIVERED + "=0 AND " + COL_MSG_SENDER_ID + "!=?",
                new String[]{peerId, peerId}, null, null, COL_MSG_TIMESTAMP + " ASC");

        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    Message m = cursorToMessage(cursor);
                    if (m != null) messages.add(m);
                }
            } finally {
                cursor.close();
            }
        }
        return messages;
    }

    public synchronized List<Message> getAllPendingMessages() {
        List<Message> messages = new ArrayList<>();
        SQLiteDatabase db = getEncryptedDb();

        Cursor cursor = db.query(TABLE_MESSAGES, null,
                COL_MSG_DELIVERED + "=0 AND " + COL_MSG_SENDER_ID + "!=" + COL_MSG_RECEIVER_ID,
                null, null, null, COL_MSG_TIMESTAMP + " ASC");

        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    Message m = cursorToMessage(cursor);
                    if (m != null) messages.add(m);
                }
            } finally {
                cursor.close();
            }
        }
        return messages;
    }

    public synchronized List<Message> getUnsentPendingMessages() {
        List<Message> messages = new ArrayList<>();
        SQLiteDatabase db = getEncryptedDb();

        Cursor cursor = db.query(TABLE_MESSAGES, null,
                COL_MSG_PENDING + "=1",
                null, null, null, COL_MSG_TIMESTAMP + " ASC");

        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    Message m = cursorToMessage(cursor);
                    if (m != null) messages.add(m);
                }
            } finally {
                cursor.close();
            }
        }
        return messages;
    }

    public synchronized void setMessagePending(String messageId, boolean pending) {
        if (messageId == null) return;
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(COL_MSG_PENDING, pending ? 1 : 0);
        db.update(TABLE_MESSAGES, cv, COL_MSG_ID + "=?", new String[]{messageId});

        Message cachedMsg = ramSingleMessageCache.get(messageId);
        if (cachedMsg != null) {
            cachedMsg.setPending(pending);
        }

        Cursor cursor = db.query(TABLE_MESSAGES, new String[]{COL_MSG_CHAT_ID}, COL_MSG_ID + "=?",
                new String[]{messageId}, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    String chatId = cursor.getString(0);
                    Chat cachedChat = ramChatCache.get(chatId);
                    if (cachedChat != null) {
                        populateLastMessageMetadata(cachedChat);
                    }
                    notifyMessagesChanged(chatId);
                    notifyChatsChanged();
                }
            } finally {
                cursor.close();
            }
        }
    }

    public synchronized void clearAllPendingFlags() {
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(COL_MSG_PENDING, 0);
        int updated = db.update(TABLE_MESSAGES, cv, COL_MSG_PENDING + "=1", null);
        if (updated > 0) {
            for (Message m : ramSingleMessageCache.values()) {
                if (m != null) m.setPending(false);
            }
            for (Chat c : ramChatCache.values()) {
                if (c != null) populateLastMessageMetadata(c);
            }
            notifyChatsChanged();
        }
    }

    public synchronized void clearPendingFlagsForChat(String chatId) {
        if (chatId == null) return;
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(COL_MSG_PENDING, 0);
        int updated = db.update(TABLE_MESSAGES, cv, COL_MSG_CHAT_ID + "=? AND " + COL_MSG_PENDING + "=1", new String[]{chatId});
        if (updated > 0) {
            for (Message m : ramSingleMessageCache.values()) {
                if (m != null && chatId.equals(m.getChatId())) {
                    m.setPending(false);
                }
            }
            Chat cachedChat = ramChatCache.get(chatId);
            if (cachedChat != null) {
                populateLastMessageMetadata(cachedChat);
            }
            notifyMessagesChanged(chatId);
            notifyChatsChanged();
        }
    }

    public synchronized void updateMessageStatus(String messageId, boolean delivered, boolean read) {
        updateMessageStatus(messageId, delivered, read, System.currentTimeMillis());
    }

    public synchronized void updateMessageStatus(String messageId, boolean delivered, boolean read, long readAt) {
        if (messageId == null) return;
        SQLiteDatabase db = getEncryptedDb();

        ContentValues cv = new ContentValues();
        cv.put(COL_MSG_PENDING, 0);
        if (delivered) cv.put(COL_MSG_DELIVERED, 1);
        if (read) {
            cv.put(COL_MSG_DELIVERED, 1);
            cv.put(COL_MSG_READ, 1);

            // Handle "after_read" vanish timer on the sender's copy!
            try {
                Message m = getMessageById(messageId);
                if (m != null) {
                    long timer = m.getVanishTimer();
                    String mode = m.getVanishMode();
                    String msgChatId = null;

                    Cursor c = db.query(TABLE_MESSAGES, new String[]{COL_MSG_CHAT_ID}, COL_MSG_ID + "=?", new String[]{messageId}, null, null, null);
                    if (c != null) {
                        try {
                            if (c.moveToFirst()) msgChatId = c.getString(0);
                        } finally {
                            c.close();
                        }
                    }

                    if (msgChatId != null) {
                        Chat ch = getChat(msgChatId);
                        if (ch != null) {
                            if (timer <= 0) timer = ch.getVanishTimer();
                            if (mode == null || mode.isEmpty()) mode = ch.getVanishMode();
                        }
                    }

                    if ("after_read".equals(mode) && timer > 0) {
                        long currentExp = m.getExpiresAt() != null ? m.getExpiresAt().toDate().getTime() : 0;
                        if (currentExp == 0) {
                            long effectiveReadAt = readAt > 0 ? readAt : System.currentTimeMillis();
                            cv.put(COL_MSG_EXPIRES_AT, effectiveReadAt + timer);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        db.update(TABLE_MESSAGES, cv, COL_MSG_ID + "=?", new String[]{messageId});

        Message cachedMsg = ramSingleMessageCache.get(messageId);
        if (cachedMsg != null) {
            cachedMsg.setPending(false);
            if (delivered || read) cachedMsg.setDelivered(true);
            if (read) cachedMsg.setRead(true);
        }

        Cursor cursor = db.query(TABLE_MESSAGES, new String[]{COL_MSG_CHAT_ID}, COL_MSG_ID + "=?",
                new String[]{messageId}, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    String chatId = cursor.getString(0);
                    Chat cachedChat = ramChatCache.get(chatId);
                    if (cachedChat != null) {
                        populateLastMessageMetadata(cachedChat);
                    }
                    notifyMessagesChanged(chatId);
                    notifyChatsChanged();
                }
            } finally {
                cursor.close();
            }
        }
    }

    public synchronized void markAllAsRead(String chatId, String currentUserId) {
        if (chatId == null) return;
        String safeUserId = currentUserId;
        if (safeUserId == null || safeUserId.trim().isEmpty()) {
            safeUserId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this.context);
        }
        if (safeUserId == null || safeUserId.trim().isEmpty()) {
            safeUserId = this.context.getSharedPreferences("veil_prefs", Context.MODE_PRIVATE).getString("user_id", null);
        }
        if (safeUserId == null || safeUserId.trim().isEmpty()) {
            safeUserId = com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(this.context).getCurrentUserId();
        }
        if (safeUserId == null) safeUserId = "";
        SQLiteDatabase db = getEncryptedDb();

        // 1. Gather all local user IDs to ensure outbound sent messages are NEVER marked read!
        java.util.Set<String> myIds = com.m3.veil.manager.AuthManager.getInstance().getMyUserIds(this.context);
        if (currentUserId != null && !currentUserId.trim().isEmpty()) {
            myIds.add(currentUserId.trim());
        }
        if (safeUserId != null && !safeUserId.trim().isEmpty()) {
            myIds.add(safeUserId.trim());
        }

        if (!myIds.isEmpty()) {
            try {
                Chat chat = getChat(chatId);
                long defaultTimer = chat != null ? chat.getVanishTimer() : 0;
                String defaultMode = chat != null ? chat.getVanishMode() : "after_send";
                long now = System.currentTimeMillis();

                StringBuilder whereSb = new StringBuilder(COL_MSG_CHAT_ID + "=? AND " + COL_MSG_READ + "=0 AND " + COL_MSG_TYPE + " NOT IN ('voice', 'image', 'video', 'document')");
                List<String> argsList = new ArrayList<>();
                argsList.add(chatId);
                for (String mId : myIds) {
                    if (mId != null && !mId.isEmpty()) {
                        whereSb.append(" AND ").append(COL_MSG_SENDER_ID).append("!=?");
                        argsList.add(mId);
                    }
                }

                Cursor cursor = db.query(TABLE_MESSAGES,
                        new String[]{COL_MSG_ID, COL_MSG_VANISH_TIMER, COL_MSG_VANISH_MODE, COL_MSG_EXPIRES_AT, COL_MSG_SENDER_ID},
                        whereSb.toString(),
                        argsList.toArray(new String[0]), null, null, null);
                if (cursor != null) {
                    try {
                        while (cursor.moveToNext()) {
                            String msgId = cursor.getString(0);
                            long vTimer = cursor.getLong(1);
                            String vMode = cursor.getString(2);
                            long expAt = cursor.getLong(3);
                            String senderId = cursor.getString(4);
                            if (com.m3.veil.manager.AuthManager.getInstance().isMyUserId(this.context, senderId)) {
                                continue;
                            }

                            if (vTimer <= 0) vTimer = defaultTimer;
                            if (vMode == null || vMode.isEmpty()) vMode = defaultMode;

                            if ("after_read".equals(vMode) && vTimer > 0 && expAt == 0) {
                                ContentValues expCv = new ContentValues();
                                expCv.put(COL_MSG_EXPIRES_AT, now + vTimer);
                                db.update(TABLE_MESSAGES, expCv, COL_MSG_ID + "=?", new String[]{msgId});
                            }
                        }
                    } finally {
                        cursor.close();
                    }
                }
            } catch (Exception ignored) {}

            ContentValues cv = new ContentValues();
            cv.put(COL_MSG_READ, 1);
            cv.put(COL_MSG_DELIVERED, 1);

            StringBuilder updateWhereSb = new StringBuilder(COL_MSG_CHAT_ID + "=? AND " + COL_MSG_TYPE + " NOT IN ('voice', 'image', 'video', 'document')");
            List<String> updateArgsList = new ArrayList<>();
            updateArgsList.add(chatId);
            for (String mId : myIds) {
                if (mId != null && !mId.isEmpty()) {
                    updateWhereSb.append(" AND ").append(COL_MSG_SENDER_ID).append("!=?");
                    updateArgsList.add(mId);
                }
            }

            db.update(TABLE_MESSAGES, cv, updateWhereSb.toString(),
                    updateArgsList.toArray(new String[0]));
            for (Message m : ramSingleMessageCache.values()) {
                if (m != null && chatId.equals(m.getChatId()) && m.getSenderId() != null) {
                    if (!com.m3.veil.manager.AuthManager.getInstance().isMyUserId(this.context, m.getSenderId()) && !myIds.contains(m.getSenderId())) {
                        if (!m.isMediaRequiringActionToRead()) {
                            m.setDelivered(true);
                            m.setRead(true);
                        }
                    }
                }
            }
        }

        ContentValues chatCv = new ContentValues();
        chatCv.put(COL_CHAT_UNREAD_COUNT, 0);
        db.update(TABLE_CHATS, chatCv, COL_CHAT_ID + "=?", new String[]{chatId});

        Chat cachedChat = ramChatCache.get(chatId);
        if (cachedChat != null) {
            cachedChat.setUnreadCount(0);
            populateLastMessageMetadata(cachedChat);
        }

        notifyMessagesChanged(chatId);
        notifyChatsChanged();
    }

    public synchronized void deleteSingleMessage(String messageId) {
        if (messageId == null) return;
        ramSingleMessageCache.remove(messageId);
        SQLiteDatabase db = getEncryptedDb();

        String chatId = null;
        Cursor cursor = db.query(TABLE_MESSAGES, new String[]{COL_MSG_CHAT_ID}, COL_MSG_ID + "=?",
                new String[]{messageId}, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) chatId = cursor.getString(0);
            } finally {
                cursor.close();
            }
        }

        db.delete(TABLE_MESSAGES, COL_MSG_ID + "=?", new String[]{messageId});
        if (chatId != null) {
            List<Message> cachedList = ramMessageListCache.get(chatId);
            if (cachedList != null) {
                for (int i = cachedList.size() - 1; i >= 0; i--) {
                    Message m = cachedList.get(i);
                    if (m != null && messageId.equals(m.getId())) {
                        cachedList.remove(i);
                    }
                }
            }
            updateChatLastMessageSummary(db, chatId);
            Chat cachedChat = ramChatCache.get(chatId);
            if (cachedChat != null) {
                populateLastMessageMetadata(cachedChat);
            }
            notifyMessagesChanged(chatId);
            notifyChatsChanged();
        }
    }

    public synchronized void clearChatMessages(String chatId) {
        if (chatId == null) return;
        ramMessageListCache.remove(chatId);
        SQLiteDatabase db = getEncryptedDb();
        db.delete(TABLE_MESSAGES, COL_MSG_CHAT_ID + "=?", new String[]{chatId});

        ContentValues chatCv = new ContentValues();
        chatCv.put(COL_CHAT_LAST_MESSAGE, "Chat cleared");
        chatCv.put(COL_CHAT_UNREAD_COUNT, 0);
        db.update(TABLE_CHATS, chatCv, COL_CHAT_ID + "=?", new String[]{chatId});

        Chat cachedChat = ramChatCache.get(chatId);
        if (cachedChat != null) {
            populateLastMessageMetadata(cachedChat);
        }

        notifyMessagesChanged(chatId);
        notifyChatsChanged();
    }

    public synchronized void markOutboundMessagesAsRead(String chatId) {
        markOutboundMessagesAsRead(chatId, System.currentTimeMillis());
    }

    public synchronized void markOutboundMessagesAsRead(String chatId, long readAt) {
        if (chatId == null) return;
        SQLiteDatabase db = getEncryptedDb();
        long effectiveReadAt = readAt > 0 ? readAt : System.currentTimeMillis();
        String myId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this.context);
        if (myId == null || myId.isEmpty()) {
            myId = this.context.getSharedPreferences("veil_prefs", Context.MODE_PRIVATE).getString("user_id", null);
        }
        if (myId == null || myId.isEmpty()) {
            myId = com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(this.context).getCurrentUserId();
        }

        try {
            Chat chat = getChat(chatId);
            long timer = chat != null ? chat.getVanishTimer() : 0;
            String mode = chat != null ? chat.getVanishMode() : "after_send";

            if ("after_read".equals(mode) && timer > 0) {
                ContentValues expCv = new ContentValues();
                expCv.put(COL_MSG_EXPIRES_AT, effectiveReadAt + timer);
                db.update(TABLE_MESSAGES, expCv,
                        COL_MSG_CHAT_ID + "=? AND " + COL_MSG_READ + "=0 AND " + COL_MSG_EXPIRES_AT + "=0",
                        new String[]{chatId});
            }
        } catch (Exception ignored) {}

        ContentValues cv = new ContentValues();
        cv.put(COL_MSG_DELIVERED, 1);
        cv.put(COL_MSG_READ, 1);
        cv.put(COL_MSG_PENDING, 0);
        // Only mark messages that were already delivered and not pending, and not media requiring action
        String mediaExclusion = " AND " + COL_MSG_TYPE + " NOT IN ('voice', 'image', 'video', 'document')";
        if (myId != null && !myId.isEmpty()) {
            db.update(TABLE_MESSAGES, cv, COL_MSG_CHAT_ID + "=? AND " + COL_MSG_SENDER_ID + "=? AND " + COL_MSG_DELIVERED + "=1 AND " + COL_MSG_PENDING + "=0" + mediaExclusion, new String[]{chatId, myId});
        } else {
            db.update(TABLE_MESSAGES, cv, COL_MSG_CHAT_ID + "=? AND " + COL_MSG_DELIVERED + "=1 AND " + COL_MSG_PENDING + "=0" + mediaExclusion, new String[]{chatId});
        }
        for (Message m : ramSingleMessageCache.values()) {
            if (m != null && chatId.equals(m.getChatId()) && (myId == null || myId.isEmpty() || myId.equals(m.getSenderId()))) {
                if (m.isDelivered() && !m.isPending() && !m.isMediaRequiringActionToRead()) {
                    m.setDelivered(true);
                    m.setRead(true);
                }
            }
        }
        Chat cachedChat = ramChatCache.get(chatId);
        if (cachedChat != null) {
            populateLastMessageMetadata(cachedChat);
        }
        notifyMessagesChanged(chatId);
        notifyChatsChanged();
    }

    /**
     * Watermark-bounded bulk read receipt (Telegram max_id semantics).
     * Marks ONLY my outbound messages in {@code chatId} that the peer has provably received:
     *  - messages whose id is explicitly listed in {@code explicitIds}, OR
     *  - messages with timestamp &lt;= {@code readUpToTs} that are already delivered and not pending.
     * Messages still in the outbox or not yet delivered are never turned blue.
     *
     * @return ids of messages whose read flag changed from 0 to 1.
     */
    public synchronized List<String> markOutboundMessagesReadUpTo(String chatId, long readUpToTs,
                                                                 Set<String> explicitIds, long readAt) {
        List<String> updated = new ArrayList<>();
        if (chatId == null) return updated;
        String myId = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this.context);
        if (myId == null || myId.isEmpty()) {
            myId = this.context.getSharedPreferences("veil_prefs", Context.MODE_PRIVATE).getString("user_id", null);
        }
        if (myId == null || myId.isEmpty()) {
            myId = com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.getInstance(this.context).getCurrentUserId();
        }
        if (myId == null || myId.isEmpty()) return updated;
        SQLiteDatabase db = getEncryptedDb();
        long effectiveReadAt = readAt > 0 ? readAt : System.currentTimeMillis();

        Cursor c = db.query(TABLE_MESSAGES,
                new String[]{COL_MSG_ID, COL_MSG_TIMESTAMP, COL_MSG_DELIVERED, COL_MSG_PENDING, COL_MSG_SENDER_ID, COL_MSG_TYPE},
                COL_MSG_CHAT_ID + "=? AND " + COL_MSG_READ + "=0",
                new String[]{chatId}, null, null, null);
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    long ts = c.getLong(1);
                    boolean delivered = c.getInt(2) == 1;
                    boolean pending = c.getInt(3) == 1;
                    String sId = c.getString(4);
                    String mType = c.getString(5);
                    // Only process outbound messages sent by the local user
                    if (sId != null && !com.m3.veil.manager.AuthManager.getInstance().isMyUserId(this.context, sId)) {
                        continue;
                    }
                    boolean isMediaAction = Message.isMediaRequiringActionToRead(mType);
                    boolean listed = explicitIds != null && explicitIds.contains(id);
                    boolean belowWatermark = !isMediaAction && readUpToTs > 0 && ts <= readUpToTs && !pending;
                    if (listed || belowWatermark) updated.add(id);
                }
            } finally {
                c.close();
            }
        }
        for (String id : updated) {
            updateMessageStatus(id, true, true, effectiveReadAt);
        }
        return updated;
    }

    public synchronized void deleteExpiredVanishMessages() {
        SQLiteDatabase db = getEncryptedDb();
        long now = System.currentTimeMillis();

        // 1. Identify which chats have expired messages
        Set<String> affectedChatIds = new HashSet<>();
        Cursor c = null;
        try {
            c = db.query(true, TABLE_MESSAGES, new String[]{COL_MSG_CHAT_ID},
                    COL_MSG_EXPIRES_AT + " > 0 AND " + COL_MSG_EXPIRES_AT + " <= ?",
                    new String[]{String.valueOf(now)}, null, null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String cid = c.getString(0);
                    if (cid != null && !cid.isEmpty()) affectedChatIds.add(cid);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }

        if (affectedChatIds.isEmpty()) {
            return;
        }

        // 2. Delete expired messages from messages table
        int deleted = db.delete(TABLE_MESSAGES, COL_MSG_EXPIRES_AT + " > 0 AND " + COL_MSG_EXPIRES_AT + " <= ?",
                new String[]{String.valueOf(now)});

        // 3. For all affected chats, resync TABLE_CHATS row and RAM cache to the latest surviving message
        for (String chatId : affectedChatIds) {
            ramMessageListCache.remove(chatId);
            updateChatLastMessageSummary(db, chatId);
            Chat cachedChat = ramChatCache.get(chatId);
            if (cachedChat != null) {
                populateLastMessageMetadata(cachedChat);
            }
        }

        if (deleted > 0) {
            notifyChatsChanged();
            for (DatabaseChangeListener l : listeners) {
                try {
                    l.onMessagesUpdated(null);
                } catch (Exception ignored) {}
            }
        }
    }

    public synchronized void updateChatLastMessageSummary(String chatId) {
        updateChatLastMessageSummary(getEncryptedDb(), chatId);
    }

    public synchronized void updateChatLastMessageSummary(SQLiteDatabase db, String chatId) {
        if (chatId == null || db == null) return;
        Cursor c = null;
        try {
            long now = System.currentTimeMillis();
            c = db.query(TABLE_MESSAGES,
                    new String[]{COL_MSG_TEXT, COL_MSG_TYPE, COL_MSG_TIMESTAMP, COL_MSG_BLINK},
                    COL_MSG_CHAT_ID + "=? AND (" + COL_MSG_EXPIRES_AT + "=0 OR " + COL_MSG_EXPIRES_AT + ">?)",
                    new String[]{chatId, String.valueOf(now)},
                    null, null,
                    COL_MSG_TIMESTAMP + " DESC",
                    "1");

            ContentValues cv = new ContentValues();
            if (c != null && c.moveToFirst()) {
                String text = c.getString(0);
                String type = c.getString(1);
                long timestamp = c.getLong(2);
                boolean isBlink = c.getInt(3) == 1;

                String summary;
                if (isBlink) {
                    summary = "⚡ Blink (View-Once)";
                } else if ("poll".equals(type)) {
                    summary = "📊 Poll: " + (text != null ? text : "");
                } else if ("call".equals(type)) {
                    boolean isVideo = text != null && text.toLowerCase().contains("video");
                    String icon = isVideo ? "📹 " : "📞 ";
                    summary = icon + (text != null ? text : "Call");
                } else if ("deleted".equals(type)) {
                    summary = "🚫 This message was deleted";
                } else if (type != null && !type.equals("text")) {
                    summary = (text != null && !text.isEmpty()) ? text : "[" + type + "]";
                } else {
                    summary = text != null ? text : "";
                }

                cv.put(COL_CHAT_LAST_MESSAGE, summary);
                if (timestamp > 0) {
                    cv.put(COL_CHAT_LAST_MESSAGE_TIME, timestamp);
                }
            } else {
                // All messages in this chat have vanished or been deleted
                cv.put(COL_CHAT_LAST_MESSAGE, "");
            }
            db.update(TABLE_CHATS, cv, COL_CHAT_ID + "=?", new String[]{chatId});
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
    }

    private Message cursorToMessage(Cursor cursor) {
        String id = cursor.getString(cursor.getColumnIndexOrThrow(COL_MSG_ID));
        String senderId = cursor.getString(cursor.getColumnIndexOrThrow(COL_MSG_SENDER_ID));
        String receiverId = cursor.getString(cursor.getColumnIndexOrThrow(COL_MSG_RECEIVER_ID));
        String text = cursor.getString(cursor.getColumnIndexOrThrow(COL_MSG_TEXT));
        String type = cursor.getString(cursor.getColumnIndexOrThrow(COL_MSG_TYPE));
        long timestampMillis = cursor.getLong(cursor.getColumnIndexOrThrow(COL_MSG_TIMESTAMP));
        boolean read = cursor.getInt(cursor.getColumnIndexOrThrow(COL_MSG_READ)) == 1;
        boolean delivered = cursor.getInt(cursor.getColumnIndexOrThrow(COL_MSG_DELIVERED)) == 1;
        boolean pending = cursor.getInt(cursor.getColumnIndexOrThrow(COL_MSG_PENDING)) == 1;
        String localPath = cursor.getString(cursor.getColumnIndexOrThrow(COL_MSG_LOCAL_PATH));
        String mediaUrl = cursor.getString(cursor.getColumnIndexOrThrow(COL_MSG_MEDIA_URL));
        String thumbnail = cursor.getString(cursor.getColumnIndexOrThrow(COL_MSG_THUMBNAIL));
        long expiresAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow(COL_MSG_EXPIRES_AT));
        String replyId = cursor.getString(cursor.getColumnIndexOrThrow(COL_MSG_REPLY_ID));
        String replyName = cursor.getString(cursor.getColumnIndexOrThrow(COL_MSG_REPLY_NAME));

        Timestamp timestamp = timestampMillis > 0 ? new Timestamp(new Date(timestampMillis)) : Timestamp.now();
        Timestamp expiresAt = expiresAtMillis > 0 ? new Timestamp(new Date(expiresAtMillis)) : null;

        Message m = new Message(id, senderId, receiverId, text, timestamp, type);
        int chatIdIdx = cursor.getColumnIndex(COL_MSG_CHAT_ID);
        if (chatIdIdx != -1) m.setChatId(cursor.getString(chatIdIdx));
        m.setRead(read);
        m.setDelivered(delivered);
        m.setPending(pending);
        m.setLocalFilePath(localPath);
        m.setMediaUrl(mediaUrl);
        m.setThumbnail(thumbnail);
        m.setExpiresAt(expiresAt);
        m.setReplyToMessageId(replyId);
        m.setReplySenderName(replyName);

        int editedIdx = cursor.getColumnIndex(COL_MSG_EDITED);
        if (editedIdx != -1) m.setEdited(cursor.getInt(editedIdx) == 1);
        int blinkIdx = cursor.getColumnIndex(COL_MSG_BLINK);
        if (blinkIdx != -1) m.setBlink(cursor.getInt(blinkIdx) == 1);
        int blinkViewedIdx = cursor.getColumnIndex(COL_MSG_BLINK_VIEWED);
        if (blinkViewedIdx != -1) m.setBlinkViewed(cursor.getInt(blinkViewedIdx) == 1);
        int starIdx = cursor.getColumnIndex(COL_MSG_STARRED);
        if (starIdx != -1) m.setStarred(cursor.getInt(starIdx) == 1);
        int pinIdx = cursor.getColumnIndex(COL_MSG_PINNED);
        if (pinIdx != -1) m.setPinned(cursor.getInt(pinIdx) == 1);
        int vfIdx = cursor.getColumnIndex(COL_MSG_VOICE_FILTER);
        if (vfIdx != -1) m.setVoiceFilter(cursor.getString(vfIdx));
        int pollIdx = cursor.getColumnIndex(COL_MSG_POLL_JSON);
        if (pollIdx != -1) m.setPollJson(cursor.getString(pollIdx));
        int sNameIdx = cursor.getColumnIndex(COL_MSG_SENDER_NAME);
        if (sNameIdx != -1) m.setSenderName(cursor.getString(sNameIdx));
        int vTimerIdx = cursor.getColumnIndex(COL_MSG_VANISH_TIMER);
        if (vTimerIdx != -1) m.setVanishTimer(cursor.getLong(vTimerIdx));
        int vModeIdx = cursor.getColumnIndex(COL_MSG_VANISH_MODE);
        if (vModeIdx != -1) m.setVanishMode(cursor.getString(vModeIdx));
        int viewCountIdx = cursor.getColumnIndex(COL_MSG_VIEW_COUNT);
        if (viewCountIdx != -1) m.setViewCount(cursor.getInt(viewCountIdx));
        int commentCountIdx = cursor.getColumnIndex(COL_MSG_COMMENT_COUNT);
        if (commentCountIdx != -1) m.setCommentCount(cursor.getInt(commentCountIdx));

        int reactIdx = cursor.getColumnIndex(COL_MSG_REACTIONS);
        if (reactIdx != -1) {
            String rJson = cursor.getString(reactIdx);
            if (rJson != null && !rJson.isEmpty()) {
                try {
                    JSONObject obj = new JSONObject(rJson);
                    Map<String, String> map = new HashMap<>();
                    Iterator<String> keys = obj.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        map.put(k, obj.optString(k));
                    }
                    m.setReactions(map);
                } catch (Exception ignored) {}
            }
        }
        return m;
    }

    // ==========================================
    // VEILBOARD POSTS / MOMENTS / CONFESSIONS
    // ==========================================

    public synchronized void saveOrUpdatePost(Post post) {
        if (post == null || post.getId() == null) return;
        SQLiteDatabase db = getEncryptedDb();

        ContentValues cv = new ContentValues();
        cv.put(COL_POST_ID, post.getId());
        cv.put(COL_POST_AUTHOR_ID, post.getAuthorId());
        cv.put(COL_POST_AUTHOR_NAME, post.getAuthorUsername());
        cv.put(COL_POST_AUTHOR_AVATAR, post.getAuthorAvatar());
        cv.put(COL_POST_CONTENT, post.getContent());
        cv.put(COL_POST_TIMESTAMP, post.getTimestamp() != null ? post.getTimestamp().toDate().getTime() : System.currentTimeMillis());
        cv.put(COL_POST_LIKES_COUNT, post.getLikesCount());
        cv.put(COL_POST_COMMENTS_COUNT, post.getCommentsCount());
        cv.put(COL_POST_TYPE, post.getType() != null ? post.getType() : "text");
        cv.put(COL_POST_IMAGE_URL, post.getImageUrl());
        cv.put(COL_POST_MOOD, post.getMoodBadge());
        cv.put(COL_POST_CATEGORY, post.getCategory());
        cv.put(COL_POST_POLL_JSON, post.getPollJson());
        cv.put(COL_POST_EXPIRES_AT, post.getExpiresAtMillis());
        cv.put(COL_POST_COMMENTS_JSON, post.getCommentsJson());

        if (post.getHashtags() != null && !post.getHashtags().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < post.getHashtags().size(); i++) {
                sb.append(post.getHashtags().get(i));
                if (i < post.getHashtags().size() - 1) sb.append(",");
            }
            cv.put(COL_POST_HASHTAGS, sb.toString());
        } else {
            cv.put(COL_POST_HASHTAGS, "");
        }

        if (post.getLikedBy() != null && !post.getLikedBy().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < post.getLikedBy().size(); i++) {
                sb.append(post.getLikedBy().get(i));
                if (i < post.getLikedBy().size() - 1) sb.append(",");
            }
            cv.put(COL_POST_LIKED_BY, sb.toString());
        } else {
            cv.put(COL_POST_LIKED_BY, "");
        }

        try {
            db.insertWithOnConflict(TABLE_POSTS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            ensureSchema(db);
            try {
                db.insertWithOnConflict(TABLE_POSTS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            } catch (Exception ignored) {}
        }
        notifyPostsChanged();
    }

    public synchronized List<Post> getAllPosts() {
        List<Post> posts = new ArrayList<>();
        SQLiteDatabase db = getEncryptedDb();
        long now = System.currentTimeMillis();

        // Delete expired moments (> 24h)
        db.delete(TABLE_POSTS, COL_POST_CATEGORY + "='moment' AND " + COL_POST_EXPIRES_AT + " > 0 AND " + COL_POST_EXPIRES_AT + " <= ?",
                new String[]{String.valueOf(now)});

        Cursor cursor = db.query(TABLE_POSTS, null, null, null, null, null,
                COL_POST_TIMESTAMP + " DESC");

        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    Post p = cursorToPost(cursor);
                    if (p != null) posts.add(p);
                }
            } finally {
                cursor.close();
            }
        }

        if (posts.isEmpty()) {
            seedInitialVeilBoardPosts();
            Cursor retry = db.query(TABLE_POSTS, null, null, null, null, null,
                    COL_POST_TIMESTAMP + " DESC");
            if (retry != null) {
                try {
                    while (retry.moveToNext()) {
                        Post p = cursorToPost(retry);
                        if (p != null) posts.add(p);
                    }
                } finally {
                    retry.close();
                }
            }
        }
        return posts;
    }

    public synchronized List<Post> getAllPosts(String categoryFilter) {
        List<Post> all = getAllPosts();
        if (categoryFilter == null || categoryFilter.isEmpty() || "all".equalsIgnoreCase(categoryFilter)) {
            return all;
        }
        List<Post> filtered = new ArrayList<>();
        for (Post p : all) {
            if (categoryFilter.equalsIgnoreCase(p.getCategory()) || categoryFilter.equalsIgnoreCase(p.getType())) {
                filtered.add(p);
            }
        }
        return filtered;
    }

    public synchronized Post getPostById(String postId) {
        if (postId == null) return null;
        SQLiteDatabase db = getEncryptedDb();
        Cursor cursor = db.query(TABLE_POSTS, null, COL_POST_ID + "=?",
                new String[]{postId}, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    return cursorToPost(cursor);
                }
            } finally {
                cursor.close();
            }
        }
        return null;
    }

    public synchronized void deletePost(String postId) {
        if (postId == null) return;
        SQLiteDatabase db = getEncryptedDb();
        db.delete(TABLE_POSTS, COL_POST_ID + "=?", new String[]{postId});
        notifyPostsChanged();
    }

    private Post cursorToPost(Cursor cursor) {
        String id = cursor.getString(cursor.getColumnIndexOrThrow(COL_POST_ID));
        String authorId = cursor.getString(cursor.getColumnIndexOrThrow(COL_POST_AUTHOR_ID));
        String authorName = cursor.getString(cursor.getColumnIndexOrThrow(COL_POST_AUTHOR_NAME));
        String authorAvatar = cursor.getString(cursor.getColumnIndexOrThrow(COL_POST_AUTHOR_AVATAR));
        String content = cursor.getString(cursor.getColumnIndexOrThrow(COL_POST_CONTENT));
        long tsMillis = cursor.getLong(cursor.getColumnIndexOrThrow(COL_POST_TIMESTAMP));
        int likes = cursor.getInt(cursor.getColumnIndexOrThrow(COL_POST_LIKES_COUNT));
        int comments = cursor.getInt(cursor.getColumnIndexOrThrow(COL_POST_COMMENTS_COUNT));
        String hashtagsStr = cursor.getString(cursor.getColumnIndexOrThrow(COL_POST_HASHTAGS));
        String type = cursor.getString(cursor.getColumnIndexOrThrow(COL_POST_TYPE));
        String imgUrl = cursor.getString(cursor.getColumnIndexOrThrow(COL_POST_IMAGE_URL));
        String likedByStr = cursor.getString(cursor.getColumnIndexOrThrow(COL_POST_LIKED_BY));

        Timestamp ts = tsMillis > 0 ? new Timestamp(new Date(tsMillis)) : Timestamp.now();
        Post p = new Post(id, authorId, authorName, authorAvatar, content, ts, type);
        p.setLikesCount(likes);
        p.setCommentsCount(comments);
        p.setImageUrl(imgUrl);

        List<String> tags = new ArrayList<>();
        if (hashtagsStr != null && !hashtagsStr.isEmpty()) {
            Collections.addAll(tags, hashtagsStr.split(","));
        }
        p.setHashtags(tags);

        List<String> likedBy = new ArrayList<>();
        if (likedByStr != null && !likedByStr.isEmpty()) {
            Collections.addAll(likedBy, likedByStr.split(","));
        }
        p.setLikedBy(likedBy);

        int moodIdx = cursor.getColumnIndex(COL_POST_MOOD);
        if (moodIdx != -1) p.setMoodBadge(cursor.getString(moodIdx));
        int catIdx = cursor.getColumnIndex(COL_POST_CATEGORY);
        if (catIdx != -1) p.setCategory(cursor.getString(catIdx));
        int pollIdx = cursor.getColumnIndex(COL_POST_POLL_JSON);
        if (pollIdx != -1) p.setPollJson(cursor.getString(pollIdx));
        int expIdx = cursor.getColumnIndex(COL_POST_EXPIRES_AT);
        if (expIdx != -1) p.setExpiresAtMillis(cursor.getLong(expIdx));
        int cJsonIdx = cursor.getColumnIndex(COL_POST_COMMENTS_JSON);
        if (cJsonIdx != -1) p.setCommentsJson(cursor.getString(cJsonIdx));

        return p;
    }

    private void seedInitialVeilBoardPosts() {
        long now = System.currentTimeMillis();

        Post p1 = new Post("seed_post_1", "veil_system_1", "@PhantomEcho", "avatar_1",
                "Welcome to VeilBoard P2P Mesh! Every post, confession, poll, and 24h Moment here is exchanged directly peer-to-peer over encrypted WebRTC DataChannels and stored in your local SQLCipher vault. #veilchat #privacy #p2p",
                new Timestamp(new Date(now - 3600_000L)), "text");
        p1.setCategory("feed");
        p1.setMoodBadge("✨ Futuristic");
        p1.setLikesCount(14);
        List<String> t1 = new ArrayList<>();
        Collections.addAll(t1, "#veilchat", "#privacy", "#p2p");
        p1.setHashtags(t1);
        saveOrUpdatePost(p1);

        Post p2 = new Post("seed_post_2", "veil_system_2", "@MidnightWhisper", "avatar_2",
                "Confession: Sometimes I switch to my secondary anonymous persona just to share my poetry without anyone knowing it's me. Having zero cloud message logs feels liberating. #confession #anonymous",
                new Timestamp(new Date(now - 1800_000L)), "confession");
        p2.setCategory("confession");
        p2.setMoodBadge("💭 Confessing");
        p2.setLikesCount(29);
        List<String> t2 = new ArrayList<>();
        Collections.addAll(t2, "#confession", "#anonymous");
        p2.setHashtags(t2);
        saveOrUpdatePost(p2);

        try {
            JSONObject poll = new JSONObject();
            poll.put("question", "What is your favorite VeilChat zero-trace feature?");
            JSONArray opts = new JSONArray();
            JSONObject o1 = new JSONObject(); o1.put("text", "⚡ Blink (View-Once) Media"); o1.put("votes", 18); opts.put(o1);
            JSONObject o2 = new JSONObject(); o2.put("text", "🎭 Multi-Persona Switcher"); o2.put("votes", 24); opts.put(o2);
            JSONObject o3 = new JSONObject(); o3.put("text", "🎙️ Voice Mask Filters"); o3.put("votes", 15); opts.put(o3);
            poll.put("options", opts);

            Post p3 = new Post("seed_post_3", "veil_system_3", "@CipherPulse", "avatar_3",
                    "What is your favorite VeilChat zero-trace feature? #poll #veilchat",
                    new Timestamp(new Date(now - 900_000L)), "poll");
            p3.setCategory("poll");
            p3.setMoodBadge("📊 Curious");
            p3.setPollJson(poll.toString());
            p3.setLikesCount(19);
            List<String> t3 = new ArrayList<>();
            Collections.addAll(t3, "#poll", "#veilchat");
            p3.setHashtags(t3);
            saveOrUpdatePost(p3);
        } catch (Exception ignored) {}

        Post moment1 = new Post("seed_moment_1", "veil_system_4", "@NeonShadow", "avatar_4",
                "Late night coding session on the encrypted mesh 🌐✨ (24h Moment)",
                new Timestamp(new Date(now - 600_000L)), "moment");
        moment1.setCategory("moment");
        moment1.setMoodBadge("🌙 Night Owl");
        moment1.setExpiresAtMillis(now + 86400_000L);
        saveOrUpdatePost(moment1);
    }

    // ==========================================
    // CALL HISTORY OPERATIONS (LOCAL SQLCIPHER)
    // ==========================================

    public static boolean isMeOrBlank(String name) {
        if (name == null) return true;
        String clean = name.trim();
        return clean.isEmpty() || "Me".equalsIgnoreCase(clean) || "You".equalsIgnoreCase(clean)
                || "local_user".equalsIgnoreCase(clean) || "null".equalsIgnoreCase(clean);
    }

    public synchronized void saveCallRecord(CallRecord record) {
        if (record == null || record.getId() == null) return;
        SQLiteDatabase db = getEncryptedDb();

        String myUid = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this.context);
        if (myUid == null || myUid.isEmpty()) myUid = "local_user";

        String callerId = record.getCallerId() != null ? record.getCallerId() : "";
        String receiverId = record.getReceiverId() != null ? record.getReceiverId() : "";
        String callerName = record.getCallerName() != null ? record.getCallerName() : "";
        String receiverName = record.getReceiverName() != null ? record.getReceiverName() : "";
        String status = record.getStatus() != null ? record.getStatus() : "completed";

        // Determine if call is outgoing or incoming
        boolean isOutgoing = "outgoing".equalsIgnoreCase(status)
                || (callerId.equals(myUid) && !receiverId.equals(myUid))
                || isMeOrBlank(callerName);

        if (receiverId.equals(myUid) || "missed".equalsIgnoreCase(status) || "incoming".equalsIgnoreCase(status)) {
            isOutgoing = false;
        }

        // Remote peer identification (NEVER save current user or 'Me' as the peer!)
        String peerId = record.getPeerId();
        if (peerId == null || peerId.isEmpty() || peerId.equals(myUid) || "local_user".equals(peerId)) {
            peerId = isOutgoing ? receiverId : callerId;
            if (peerId == null || peerId.isEmpty() || peerId.equals(myUid) || "local_user".equals(peerId)) {
                peerId = isOutgoing ? callerId : receiverId;
            }
        }

        String peerName = record.getPeerName();
        if (isMeOrBlank(peerName)) {
            peerName = isOutgoing ? receiverName : callerName;
        }
        if (isMeOrBlank(peerName)) {
            peerName = isOutgoing ? callerName : receiverName;
        }
        if (isMeOrBlank(peerName) && peerId != null && !peerId.isEmpty() && !peerId.equals(myUid) && !"local_user".equals(peerId)) {
            Chat c = getChat(peerId);
            if (c != null && c.getName() != null && !isMeOrBlank(c.getName())) {
                peerName = c.getName().trim();
            }
        }
        if (isMeOrBlank(peerName)) {
            peerName = "Veil Contact";
        }

        ContentValues cv = new ContentValues();
        cv.put(COL_CALL_ID, record.getId());
        cv.put(COL_CALL_PEER_ID, peerId);
        cv.put(COL_CALL_PEER_NAME, peerName);
        cv.put(COL_CALL_TYPE, record.getCallType() != null ? record.getCallType() : "audio");
        cv.put(COL_CALL_TIMESTAMP, record.getTimestamp() != null ? record.getTimestamp().toDate().getTime() : System.currentTimeMillis());
        cv.put(COL_CALL_DURATION, String.valueOf(record.getDurationSeconds()));
        cv.put(COL_CALL_CALLER_ID, callerId);
        cv.put(COL_CALL_CALLER_NAME, callerName);
        cv.put(COL_CALL_RECEIVER_ID, receiverId);
        cv.put(COL_CALL_RECEIVER_NAME, receiverName);
        cv.put(COL_CALL_STATUS, status);
        cv.put(COL_CALL_IS_OUTGOING, isOutgoing ? 1 : 0);

        try {
            db.insertWithOnConflict(TABLE_CALLS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            ensureSchema(db);
            try {
                db.insertWithOnConflict(TABLE_CALLS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            } catch (Exception ignored) {}
        }
        notifyCallsChanged();

        // Save call as a chat message in the conversation (WhatsApp style)
        saveCallAsChatMessage(record);
    }

    private void saveCallAsChatMessage(CallRecord record) {
        if (record == null || record.getId() == null) return;
        String callerId = record.getCallerId();
        String receiverId = record.getReceiverId();
        if (callerId == null || receiverId == null || callerId.isEmpty() || receiverId.isEmpty()) {
            return;
        }

        String chatId = com.m3.veil.webrtc.WebRtcManager.getChatId(callerId, receiverId);
        String msgId = "call_" + record.getId();

        boolean isVideo = "video".equalsIgnoreCase(record.getCallType());
        String status = record.getStatus() != null ? record.getStatus() : "completed";
        int duration = record.getDurationSeconds();

        String callText;
        if ("missed".equalsIgnoreCase(status)) {
            callText = isVideo ? "Missed video call" : "Missed voice call";
        } else if ("declined".equalsIgnoreCase(status)) {
            callText = isVideo ? "Declined video call" : "Declined voice call";
        } else if ("busy".equalsIgnoreCase(status)) {
            callText = isVideo ? "Video call (Busy)" : "Voice call (Busy)";
        } else if ("failed".equalsIgnoreCase(status)) {
            callText = isVideo ? "Failed video call" : "Failed voice call";
        } else if (duration > 0) {
            callText = isVideo ? "Video call" : "Voice call";
        } else {
            callText = isVideo ? "Cancelled video call" : "Cancelled voice call";
        }

        org.json.JSONObject callJson = new org.json.JSONObject();
        try {
            callJson.put("callId", record.getId());
            callJson.put("callType", record.getCallType() != null ? record.getCallType() : "audio");
            callJson.put("status", status);
            callJson.put("duration", duration);
            callJson.put("callerId", callerId);
            callJson.put("callerName", record.getCallerName());
            callJson.put("receiverId", receiverId);
            callJson.put("receiverName", record.getReceiverName());
        } catch (Exception ignored) {}

        Chat chat = getChat(chatId);
        if (chat == null) {
            chat = new Chat();
            chat.setId(chatId);
            String myUid = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this.context);
            if (myUid == null || myUid.isEmpty()) myUid = "local_user";
            boolean isOutgoing = callerId.equals(myUid);
            String name = isOutgoing ? record.getReceiverName() : record.getCallerName();
            if (isMeOrBlank(name)) {
                name = record.getPeerName();
            }
            if (isMeOrBlank(name)) {
                name = "Veil Contact";
            }
            chat.setName(name);
            chat.setType("direct");
            java.util.List<String> participants = new java.util.ArrayList<>();
            participants.add(callerId);
            participants.add(receiverId);
            chat.setParticipants(participants);
            saveOrUpdateChat(chat);
        }

        Message msg = new Message();
        msg.setId(msgId);
        msg.setSenderId(callerId);
        msg.setReceiverId(receiverId);
        msg.setSenderName(record.getCallerName());
        msg.setText(callText);
        msg.setType("call");
        msg.setPollJson(callJson.toString());
        msg.setTimestamp(record.getTimestamp() != null ? record.getTimestamp() : com.google.firebase.Timestamp.now());
        msg.setDelivered(false);
        msg.setRead(false);

        saveMessage(chatId, msg);
    }

    public synchronized List<CallRecord> getAllCalls() {
        return getAllCallRecords();
    }

    public synchronized List<CallRecord> getAllCallRecords() {
        List<CallRecord> records = new ArrayList<>();
        SQLiteDatabase db = getEncryptedDb();
        Cursor cursor = db.query(TABLE_CALLS, null, null, null, null, null, COL_CALL_TIMESTAMP + " DESC");
        if (cursor != null) {
            try {
                int colId = cursor.getColumnIndex(COL_CALL_ID);
                int colPeerId = cursor.getColumnIndex(COL_CALL_PEER_ID);
                int colPeerName = cursor.getColumnIndex(COL_CALL_PEER_NAME);
                int colType = cursor.getColumnIndex(COL_CALL_TYPE);
                int colTs = cursor.getColumnIndex(COL_CALL_TIMESTAMP);
                int colDur = cursor.getColumnIndex(COL_CALL_DURATION);
                int colCallerId = cursor.getColumnIndex(COL_CALL_CALLER_ID);
                int colCallerName = cursor.getColumnIndex(COL_CALL_CALLER_NAME);
                int colReceiverId = cursor.getColumnIndex(COL_CALL_RECEIVER_ID);
                int colReceiverName = cursor.getColumnIndex(COL_CALL_RECEIVER_NAME);
                int colStatus = cursor.getColumnIndex(COL_CALL_STATUS);
                int colIsOut = cursor.getColumnIndex(COL_CALL_IS_OUTGOING);

                String myUid = com.m3.veil.manager.AuthManager.getInstance().getCurrentUserId(this.context);
                if (myUid == null || myUid.isEmpty()) myUid = "local_user";

                while (cursor.moveToNext()) {
                    String id = colId != -1 ? cursor.getString(colId) : "";
                    String peerId = colPeerId != -1 ? cursor.getString(colPeerId) : "";
                    String peerName = colPeerName != -1 ? cursor.getString(colPeerName) : "";
                    String type = colType != -1 ? cursor.getString(colType) : "audio";
                    long ts = colTs != -1 ? cursor.getLong(colTs) : System.currentTimeMillis();
                    String durationStr = colDur != -1 ? cursor.getString(colDur) : "0";
                    int durSec = 0;
                    try {
                        durSec = Integer.parseInt(durationStr);
                    } catch (Exception ignored) {}

                    String status = (colStatus != -1 && !cursor.isNull(colStatus)) ? cursor.getString(colStatus) : "completed";
                    int isOutgoingInt = (colIsOut != -1 && !cursor.isNull(colIsOut)) ? cursor.getInt(colIsOut) : -1;

                    boolean isOutgoing;
                    if (isOutgoingInt != -1) {
                        isOutgoing = (isOutgoingInt == 1);
                    } else {
                        isOutgoing = "outgoing".equalsIgnoreCase(status);
                    }

                    String callerId = (colCallerId != -1 && !cursor.isNull(colCallerId)) ? cursor.getString(colCallerId) : (isOutgoing ? myUid : peerId);
                    String callerName = (colCallerName != -1 && !cursor.isNull(colCallerName)) ? cursor.getString(colCallerName) : (isOutgoing ? "Me" : peerName);
                    String receiverId = (colReceiverId != -1 && !cursor.isNull(colReceiverId)) ? cursor.getString(colReceiverId) : (isOutgoing ? peerId : myUid);
                    String receiverName = (colReceiverName != -1 && !cursor.isNull(colReceiverName)) ? cursor.getString(colReceiverName) : (isOutgoing ? peerName : "Me");

                    // Real-time self-healing check for legacy rows where peerName was saved as "Me" or was empty
                    if (isMeOrBlank(peerName) || peerId == null || peerId.isEmpty() || peerId.equals(myUid) || "local_user".equals(peerId)) {
                        String realPeerId = isOutgoing ? receiverId : callerId;
                        if (realPeerId == null || realPeerId.isEmpty() || realPeerId.equals(myUid) || "local_user".equals(realPeerId)) {
                            realPeerId = isOutgoing ? callerId : receiverId;
                        }
                        if (realPeerId != null && !realPeerId.isEmpty() && !realPeerId.equals(myUid) && !"local_user".equals(realPeerId)) {
                            peerId = realPeerId;
                            Chat c = getChat(peerId);
                            if (c != null && c.getName() != null && !isMeOrBlank(c.getName())) {
                                peerName = c.getName().trim();
                            }
                        }
                        if (isMeOrBlank(peerName)) {
                            peerName = isOutgoing ? receiverName : callerName;
                        }
                        if (isMeOrBlank(peerName)) {
                            peerName = isOutgoing ? callerName : receiverName;
                        }
                        if (isMeOrBlank(peerName)) {
                            peerName = "Veil Contact";
                        }

                        // Permanently repair the row in database
                        try {
                            ContentValues fixCv = new ContentValues();
                            fixCv.put(COL_CALL_PEER_ID, peerId);
                            fixCv.put(COL_CALL_PEER_NAME, peerName);
                            db.update(TABLE_CALLS, fixCv, COL_CALL_ID + "=?", new String[]{id});
                        } catch (Throwable ignored) {}
                    }

                    // Ensure neither party displays "Me" when looked up for peer
                    if (isOutgoing && (isMeOrBlank(receiverName))) {
                        receiverName = peerName;
                    } else if (!isOutgoing && (isMeOrBlank(callerName))) {
                        callerName = peerName;
                    }

                    records.add(new CallRecord(id, peerId, peerName, callerId, callerName, receiverId, receiverName, type, status, durSec, new Timestamp(new Date(ts))));
                }
            } finally {
                cursor.close();
            }
        }
        return records;
    }

    public synchronized void deleteCall(String callId) {
        SQLiteDatabase db = getEncryptedDb();
        if (db == null || !db.isOpen()) return;
        db.delete(TABLE_CALLS, COL_CALL_ID + " = ?", new String[]{callId});
        notifyCallsChanged();
    }

    public synchronized void clearAllCalls() {
        SQLiteDatabase db = getEncryptedDb();
        if (db == null || !db.isOpen()) return;
        db.delete(TABLE_CALLS, null, null);
        notifyCallsChanged();
    }

    // ==========================================
    // PRIVATE NOTES VAULT / DIARY / FUTURE SELF
    // ==========================================

    public static class VaultNote {
        public String id;
        public String title;
        public String content;
        public String noteType; // "vault", "diary", "future_self"
        public long timestamp;
        public long unlockAt;
        public long unlockAtMillis;

        public VaultNote(String id, String title, String content, String noteType, long timestamp, long unlockAt) {
            this.id = id;
            this.title = title;
            this.content = content;
            this.noteType = noteType;
            this.timestamp = timestamp;
            this.unlockAt = unlockAt;
            this.unlockAtMillis = unlockAt;
        }
    }

    public synchronized void saveVaultNote(String id, String title, String content, String noteType, long unlockAtMillis) {
        saveVaultNote(new VaultNote(id, title, content, noteType, System.currentTimeMillis(), unlockAtMillis));
    }

    public synchronized void saveVaultNote(VaultNote note) {
        if (note == null || note.id == null) return;
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(COL_NOTE_ID, note.id);
        cv.put(COL_NOTE_TITLE, note.title);
        cv.put(COL_NOTE_CONTENT, note.content);
        cv.put(COL_NOTE_TYPE, note.noteType != null ? note.noteType : "vault");
        cv.put(COL_NOTE_TIMESTAMP, note.timestamp);
        cv.put(COL_NOTE_UNLOCK_AT, note.unlockAt);
        db.insertWithOnConflict(TABLE_NOTES, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized List<VaultNote> getAllVaultNotes() {
        List<VaultNote> notes = new ArrayList<>();
        SQLiteDatabase db = getEncryptedDb();
        Cursor cursor = db.query(TABLE_NOTES, null, null, null, null, null, COL_NOTE_TIMESTAMP + " DESC");
        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    notes.add(new VaultNote(
                            cursor.getString(cursor.getColumnIndexOrThrow(COL_NOTE_ID)),
                            cursor.getString(cursor.getColumnIndexOrThrow(COL_NOTE_TITLE)),
                            cursor.getString(cursor.getColumnIndexOrThrow(COL_NOTE_CONTENT)),
                            cursor.getString(cursor.getColumnIndexOrThrow(COL_NOTE_TYPE)),
                            cursor.getLong(cursor.getColumnIndexOrThrow(COL_NOTE_TIMESTAMP)),
                            cursor.getLong(cursor.getColumnIndexOrThrow(COL_NOTE_UNLOCK_AT))
                    ));
                }
            } finally {
                cursor.close();
            }
        }
        return notes;
    }

    public synchronized void deleteVaultNote(String noteId) {
        if (noteId == null) return;
        SQLiteDatabase db = getEncryptedDb();
        db.delete(TABLE_NOTES, COL_NOTE_ID + "=?", new String[]{noteId});
    }

    // ==========================================
    // P2P OUTBOX QUEUE OPERATIONS
    // ==========================================

    public static class OutboxItem {
        public String id;
        public String chatId;
        public String recipientId;
        public int packetType;
        public byte[] payload;
        public long timestamp;
        public int retryCount;
        public long lastAttempt;
        public String envelopeId;

        public OutboxItem() {}

        public OutboxItem(String id, String chatId, String recipientId, int packetType, byte[] payload, long timestamp) {
            this.id = id;
            this.chatId = chatId;
            this.recipientId = recipientId;
            this.packetType = packetType;
            this.payload = payload;
            this.timestamp = timestamp;
            this.retryCount = 0;
            this.lastAttempt = 0;
            this.envelopeId = null;
        }
    }

    public synchronized void enqueueOutboxItem(OutboxItem item) {
        if (item == null || item.id == null) return;
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(COL_OUTBOX_ID, item.id);
        cv.put(COL_OUTBOX_CHAT_ID, item.chatId);
        cv.put(COL_OUTBOX_RECIPIENT_ID, item.recipientId);
        cv.put(COL_OUTBOX_PACKET_TYPE, item.packetType);
        cv.put(COL_OUTBOX_PAYLOAD, item.payload);
        cv.put(COL_OUTBOX_TIMESTAMP, item.timestamp);
        cv.put(COL_OUTBOX_RETRY_COUNT, item.retryCount);
        cv.put(COL_OUTBOX_LAST_ATTEMPT, item.lastAttempt);
        cv.put(COL_OUTBOX_ENVELOPE_ID, item.envelopeId);
        db.insertWithOnConflict(TABLE_OUTBOX, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized List<OutboxItem> getPendingOutboxItems(int limit) {
        List<OutboxItem> list = new ArrayList<>();
        SQLiteDatabase db = getEncryptedDb();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT * FROM " + TABLE_OUTBOX + " ORDER BY " + COL_OUTBOX_TIMESTAMP + " ASC LIMIT " + limit, null);
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    OutboxItem item = mapCursorToOutboxItem(cursor);
                    if (item != null) list.add(item);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching pending outbox items", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return list;
    }

    public synchronized List<OutboxItem> getPendingOutboxItemsForPeer(String peerId) {
        List<OutboxItem> list = new ArrayList<>();
        if (peerId == null || peerId.isEmpty()) return list;
        SQLiteDatabase db = getEncryptedDb();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_OUTBOX, null, COL_OUTBOX_RECIPIENT_ID + "=?", new String[]{peerId}, null, null, COL_OUTBOX_TIMESTAMP + " ASC");
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    OutboxItem item = mapCursorToOutboxItem(cursor);
                    if (item != null) list.add(item);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching outbox items for peer: " + peerId, e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return list;
    }

    public synchronized void updateOutboxAttempt(String id, int retryCount, long timestamp) {
        if (id == null) return;
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(COL_OUTBOX_RETRY_COUNT, retryCount);
        cv.put(COL_OUTBOX_LAST_ATTEMPT, timestamp);
        db.update(TABLE_OUTBOX, cv, COL_OUTBOX_ID + "=?", new String[]{id});
    }

    public synchronized void setOutboxEnvelopeId(String id, String envelopeId) {
        if (id == null) return;
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(COL_OUTBOX_ENVELOPE_ID, envelopeId);
        db.update(TABLE_OUTBOX, cv, COL_OUTBOX_ID + "=?", new String[]{id});
    }

    public synchronized void removeOutboxItem(String id) {
        if (id == null) return;
        SQLiteDatabase db = getEncryptedDb();
        db.delete(TABLE_OUTBOX, COL_OUTBOX_ID + "=?", new String[]{id});
    }

    public synchronized void removeOutboxItemByEnvelopeId(String envelopeId) {
        if (envelopeId == null) return;
        SQLiteDatabase db = getEncryptedDb();
        db.delete(TABLE_OUTBOX, COL_OUTBOX_ENVELOPE_ID + "=?", new String[]{envelopeId});
    }

    public synchronized OutboxItem getOutboxItem(String id) {
        if (id == null) return null;
        SQLiteDatabase db = getEncryptedDb();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_OUTBOX, null, COL_OUTBOX_ID + "=?", new String[]{id}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                return mapCursorToOutboxItem(cursor);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching outbox item: " + id, e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    public synchronized int getOutboxCount() {
        SQLiteDatabase db = getEncryptedDb();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_OUTBOX, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return 0;
    }

    private OutboxItem mapCursorToOutboxItem(Cursor cursor) {
        try {
            OutboxItem item = new OutboxItem();
            item.id = cursor.getString(cursor.getColumnIndexOrThrow(COL_OUTBOX_ID));
            item.chatId = cursor.getString(cursor.getColumnIndexOrThrow(COL_OUTBOX_CHAT_ID));
            item.recipientId = cursor.getString(cursor.getColumnIndexOrThrow(COL_OUTBOX_RECIPIENT_ID));
            item.packetType = cursor.getInt(cursor.getColumnIndexOrThrow(COL_OUTBOX_PACKET_TYPE));
            item.payload = cursor.getBlob(cursor.getColumnIndexOrThrow(COL_OUTBOX_PAYLOAD));
            item.timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_OUTBOX_TIMESTAMP));
            item.retryCount = cursor.getInt(cursor.getColumnIndexOrThrow(COL_OUTBOX_RETRY_COUNT));
            item.lastAttempt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_OUTBOX_LAST_ATTEMPT));
            item.envelopeId = cursor.getString(cursor.getColumnIndexOrThrow(COL_OUTBOX_ENVELOPE_ID));
            return item;
        } catch (Exception e) {
            return null;
        }
    }

    // ==========================================
    // RESUMABLE P2P FILE TRANSFERS
    // ==========================================

    public static class FileTransferRecord {
        public String transferId;
        public String messageId;
        public String filePath;
        public String fileName;
        public String mimeType;
        public long totalBytes;
        public int chunkSize;
        public int totalChunks;
        public byte[] bitsetBytes;
        public boolean isUpload;
        public String status;
        public String peerId;
        public long updatedAt;
    }

    public synchronized void saveOrUpdateFileTransfer(FileTransferRecord record) {
        if (record == null || record.transferId == null) return;
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(COL_FT_ID, record.transferId);
        cv.put(COL_FT_MSG_ID, record.messageId);
        cv.put(COL_FT_FILE_PATH, record.filePath);
        cv.put(COL_FT_FILE_NAME, record.fileName);
        cv.put(COL_FT_MIME_TYPE, record.mimeType);
        cv.put(COL_FT_TOTAL_BYTES, record.totalBytes);
        cv.put(COL_FT_CHUNK_SIZE, record.chunkSize);
        cv.put(COL_FT_TOTAL_CHUNKS, record.totalChunks);
        cv.put(COL_FT_BITSET, record.bitsetBytes);
        cv.put(COL_FT_IS_UPLOAD, record.isUpload ? 1 : 0);
        cv.put(COL_FT_STATUS, record.status);
        cv.put(COL_FT_PEER_ID, record.peerId);
        cv.put(COL_FT_UPDATED_AT, System.currentTimeMillis());
        db.insertWithOnConflict(TABLE_FILE_TRANSFERS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized FileTransferRecord getFileTransfer(String transferId) {
        if (transferId == null) return null;
        SQLiteDatabase db = getEncryptedDb();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_FILE_TRANSFERS, null, COL_FT_ID + "=?", new String[]{transferId}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                FileTransferRecord record = new FileTransferRecord();
                record.transferId = cursor.getString(cursor.getColumnIndexOrThrow(COL_FT_ID));
                record.messageId = cursor.getString(cursor.getColumnIndexOrThrow(COL_FT_MSG_ID));
                record.filePath = cursor.getString(cursor.getColumnIndexOrThrow(COL_FT_FILE_PATH));
                record.fileName = cursor.getString(cursor.getColumnIndexOrThrow(COL_FT_FILE_NAME));
                record.mimeType = cursor.getString(cursor.getColumnIndexOrThrow(COL_FT_MIME_TYPE));
                record.totalBytes = cursor.getLong(cursor.getColumnIndexOrThrow(COL_FT_TOTAL_BYTES));
                record.chunkSize = cursor.getInt(cursor.getColumnIndexOrThrow(COL_FT_CHUNK_SIZE));
                record.totalChunks = cursor.getInt(cursor.getColumnIndexOrThrow(COL_FT_TOTAL_CHUNKS));
                record.bitsetBytes = cursor.getBlob(cursor.getColumnIndexOrThrow(COL_FT_BITSET));
                record.isUpload = cursor.getInt(cursor.getColumnIndexOrThrow(COL_FT_IS_UPLOAD)) == 1;
                record.status = cursor.getString(cursor.getColumnIndexOrThrow(COL_FT_STATUS));
                record.peerId = cursor.getString(cursor.getColumnIndexOrThrow(COL_FT_PEER_ID));
                record.updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_FT_UPDATED_AT));
                return record;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching file transfer: " + transferId, e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    public synchronized void updateFileTransferBitSet(String transferId, byte[] bitsetBytes, String status) {
        if (transferId == null) return;
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        if (bitsetBytes != null) cv.put(COL_FT_BITSET, bitsetBytes);
        if (status != null) cv.put(COL_FT_STATUS, status);
        cv.put(COL_FT_UPDATED_AT, System.currentTimeMillis());
        db.update(TABLE_FILE_TRANSFERS, cv, COL_FT_ID + "=?", new String[]{transferId});
    }

    public synchronized void removeFileTransfer(String transferId) {
        if (transferId == null) return;
        SQLiteDatabase db = getEncryptedDb();
        db.delete(TABLE_FILE_TRANSFERS, COL_FT_ID + "=?", new String[]{transferId});
    }

    // ==========================================
    // PURE P2P MESH STORE-AND-FORWARD ENVELOPES
    // ==========================================

    public static class MeshEnvelopeRecord {
        public String envelopeId;
        public String blindMailboxHash;
        public byte[] sealedPayload;
        public int hopCount;
        public int maxHops;
        public long createdAt;
        public long expiresAt;
        public String originPeerId;
    }

    public synchronized void saveMeshEnvelope(MeshEnvelopeRecord record) {
        if (record == null || record.envelopeId == null) return;
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(COL_MESH_ID, record.envelopeId);
        cv.put(COL_MESH_MAILBOX, record.blindMailboxHash);
        cv.put(COL_MESH_PAYLOAD, record.sealedPayload);
        cv.put(COL_MESH_HOP_COUNT, record.hopCount);
        cv.put(COL_MESH_MAX_HOPS, record.maxHops);
        cv.put(COL_MESH_CREATED_AT, record.createdAt);
        cv.put(COL_MESH_EXPIRES_AT, record.expiresAt);
        cv.put(COL_MESH_ORIGIN_PEER, record.originPeerId);
        db.insertWithOnConflict(TABLE_MESH_ENVELOPES, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized MeshEnvelopeRecord getMeshEnvelope(String envelopeId) {
        if (envelopeId == null) return null;
        SQLiteDatabase db = getEncryptedDb();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_MESH_ENVELOPES, null, COL_MESH_ID + "=?", new String[]{envelopeId}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                return mapCursorToMeshEnvelope(cursor);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching mesh envelope: " + envelopeId, e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    public synchronized List<MeshEnvelopeRecord> getMeshEnvelopesForMailbox(String blindMailboxHash) {
        List<MeshEnvelopeRecord> list = new ArrayList<>();
        if (blindMailboxHash == null || blindMailboxHash.isEmpty()) return list;
        SQLiteDatabase db = getEncryptedDb();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_MESH_ENVELOPES, null, COL_MESH_MAILBOX + "=?", new String[]{blindMailboxHash}, null, null, COL_MESH_CREATED_AT + " ASC");
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    MeshEnvelopeRecord rec = mapCursorToMeshEnvelope(cursor);
                    if (rec != null) list.add(rec);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching envelopes for mailbox", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return list;
    }

    public synchronized List<MeshEnvelopeRecord> getGossipableMeshEnvelopes(int maxHops, int limit) {
        List<MeshEnvelopeRecord> list = new ArrayList<>();
        SQLiteDatabase db = getEncryptedDb();
        Cursor cursor = null;
        long now = System.currentTimeMillis();
        try {
            cursor = db.rawQuery("SELECT * FROM " + TABLE_MESH_ENVELOPES + " WHERE " + COL_MESH_HOP_COUNT + " < ? AND " + COL_MESH_EXPIRES_AT + " > ? ORDER BY " + COL_MESH_CREATED_AT + " ASC LIMIT ?",
                    new String[]{String.valueOf(maxHops), String.valueOf(now), String.valueOf(limit)});
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    MeshEnvelopeRecord rec = mapCursorToMeshEnvelope(cursor);
                    if (rec != null) list.add(rec);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching gossipable mesh envelopes", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return list;
    }

    public synchronized void incrementEnvelopeHop(String envelopeId) {
        if (envelopeId == null) return;
        SQLiteDatabase db = getEncryptedDb();
        db.execSQL("UPDATE " + TABLE_MESH_ENVELOPES + " SET " + COL_MESH_HOP_COUNT + " = " + COL_MESH_HOP_COUNT + " + 1 WHERE " + COL_MESH_ID + " = ?", new Object[]{envelopeId});
    }

    public synchronized void removeMeshEnvelope(String envelopeId) {
        if (envelopeId == null) return;
        SQLiteDatabase db = getEncryptedDb();
        db.delete(TABLE_MESH_ENVELOPES, COL_MESH_ID + "=?", new String[]{envelopeId});
    }

    public synchronized String purgeMeshEnvelopeForMessage(String messageId) {
        if (messageId == null) return null;
        SQLiteDatabase db = getEncryptedDb();
        String purgedEnvId = null;
        try {
            Cursor c = db.query(TABLE_OUTBOX, new String[]{COL_OUTBOX_ENVELOPE_ID},
                    COL_OUTBOX_ID + "=?", new String[]{messageId}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        String envId = c.getString(0);
                        if (envId != null && !envId.isEmpty()) {
                            removeMeshEnvelope(envId);
                            purgedEnvId = envId;
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) {}
        return purgedEnvId;
    }

    public synchronized void pruneExpiredMeshEnvelopes() {
        SQLiteDatabase db = getEncryptedDb();
        long now = System.currentTimeMillis();
        db.delete(TABLE_MESH_ENVELOPES, COL_MESH_EXPIRES_AT + "<=?", new String[]{String.valueOf(now)});
    }

    private MeshEnvelopeRecord mapCursorToMeshEnvelope(Cursor cursor) {
        try {
            MeshEnvelopeRecord rec = new MeshEnvelopeRecord();
            rec.envelopeId = cursor.getString(cursor.getColumnIndexOrThrow(COL_MESH_ID));
            rec.blindMailboxHash = cursor.getString(cursor.getColumnIndexOrThrow(COL_MESH_MAILBOX));
            rec.sealedPayload = cursor.getBlob(cursor.getColumnIndexOrThrow(COL_MESH_PAYLOAD));
            rec.hopCount = cursor.getInt(cursor.getColumnIndexOrThrow(COL_MESH_HOP_COUNT));
            rec.maxHops = cursor.getInt(cursor.getColumnIndexOrThrow(COL_MESH_MAX_HOPS));
            rec.createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_MESH_CREATED_AT));
            rec.expiresAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_MESH_EXPIRES_AT));
            rec.originPeerId = cursor.getString(cursor.getColumnIndexOrThrow(COL_MESH_ORIGIN_PEER));
            return rec;
        } catch (Exception e) {
            return null;
        }
    }

    // ==========================================
    // BACKUP & SECURE PANIC WIPE
    // ==========================================

    public synchronized String exportVaultSummaryJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", DATABASE_VERSION);
            root.put("exportedAt", System.currentTimeMillis());

            JSONArray chatsArr = new JSONArray();
            for (Chat c : getAllChats()) {
                JSONObject o = new JSONObject();
                o.put("id", c.getId());
                o.put("name", c.getName());
                o.put("lastMessage", c.getLastMessage());
                o.put("type", c.getType());
                chatsArr.put(o);
            }
            root.put("chats", chatsArr);

            JSONArray notesArr = new JSONArray();
            for (VaultNote n : getAllVaultNotes()) {
                JSONObject o = new JSONObject();
                o.put("id", n.id);
                o.put("title", n.title);
                o.put("content", n.content);
                o.put("noteType", n.noteType);
                o.put("timestamp", n.timestamp);
                o.put("unlockAt", n.unlockAt);
                notesArr.put(o);
            }
            root.put("notes", notesArr);
            return root.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public synchronized void secureWipeAllData() {
        SQLiteDatabase db = getEncryptedDb();
        db.delete(TABLE_MESSAGES, null, null);
        db.delete(TABLE_CHATS, null, null);
        db.delete(TABLE_POSTS, null, null);
        db.delete(TABLE_CALLS, null, null);
        db.delete(TABLE_NOTES, null, null);
        db.delete(TABLE_OUTBOX, null, null);
        db.delete(TABLE_FILE_TRANSFERS, null, null);
        db.delete(TABLE_MESH_ENVELOPES, null, null);
        db.delete(TABLE_STATUSES, null, null);
        db.delete(TABLE_STATUS_VIEWS, null, null);
        db.delete(TABLE_STATUS_CHUNKS, null, null);
        db.delete(TABLE_STATUS_GOSSIP_QUEUE, null, null);
        notifyChatsChanged();
        notifyPostsChanged();
        notifyCallsChanged();
        notifyStatusesChanged();
    }

    // ==========================================
    // 100% P2P STATUS REELS OPERATIONS (PASS 2)
    // ==========================================

    public static class StatusGossipQueueItem {
        public String queueId;
        public String statusId;
        public String targetPeerId;
        public byte[] payloadBlob;
        public int retryCount;
        public long lastAttemptMs;

        public StatusGossipQueueItem() {}

        public StatusGossipQueueItem(String queueId, String statusId, String targetPeerId, byte[] payloadBlob, int retryCount, long lastAttemptMs) {
            this.queueId = queueId;
            this.statusId = statusId;
            this.targetPeerId = targetPeerId;
            this.payloadBlob = payloadBlob;
            this.retryCount = retryCount;
            this.lastAttemptMs = lastAttemptMs;
        }
    }

    public synchronized void saveStatusSegment(StatusController.StatusSegment seg) {
        if (seg == null || seg.id == null) return;
        SQLiteDatabase db = getEncryptedDb();

        ContentValues cv = new ContentValues();
        cv.put(COL_STATUS_ID, seg.id);
        cv.put(COL_STATUS_AUTHOR_ID, seg.authorId);
        cv.put(COL_STATUS_AUTHOR_NAME, seg.authorName);
        cv.put(COL_STATUS_CONTENT_TEXT, seg.contentText);
        cv.put(COL_STATUS_MEDIA_TYPE, seg.mediaType != null ? seg.mediaType.wire : "text");
        cv.put(COL_STATUS_MEDIA_FILE_PATH, seg.mediaFilePath);
        cv.put(COL_STATUS_ENCRYPTED_FILE_PATH, seg.encryptedFilePath);
        cv.put(COL_STATUS_THUMB_BLOB, seg.thumbBlob);
        cv.put(COL_STATUS_BG_COLOR_HEX, seg.bgColorHex);
        cv.put(COL_STATUS_FONT_STYLE_INDEX, seg.fontStyleIndex);
        cv.put(COL_STATUS_TIMESTAMP_MS, seg.timestampMs);
        cv.put(COL_STATUS_EXPIRES_AT_MS, seg.expiresAtMs);
        cv.put(COL_STATUS_TOTAL_BYTES, seg.totalBytes);
        cv.put(COL_STATUS_TOTAL_CHUNKS, seg.totalChunks);
        cv.put(COL_STATUS_PRIVACY_ACL_MODE, seg.privacyAcl != null ? seg.privacyAcl.code : 0);
        cv.put(COL_STATUS_IS_MINE, seg.isMine ? 1 : 0);
        cv.put(COL_STATUS_IS_VIEWED, seg.isViewed ? 1 : 0);
        cv.put(COL_STATUS_VIEW_COUNT, seg.viewCount);
        cv.put(COL_STATUS_AES_KEY_WRAPPED, seg.aesKeyWrapped);
        cv.put(COL_STATUS_IV_BASE64, seg.ivBase64);

        db.insertWithOnConflict(TABLE_STATUSES, null, cv, SQLiteDatabase.CONFLICT_REPLACE);

        if (seg.viewers != null && !seg.viewers.isEmpty()) {
            for (StatusController.StatusViewer v : seg.viewers) {
                saveStatusView(seg.id, v);
            }
        }
        notifyStatusesChanged();
    }

    public synchronized List<StatusController.StatusSegment> getAllActiveStatusSegments() {
        List<StatusController.StatusSegment> list = new ArrayList<>();
        SQLiteDatabase db = getEncryptedDb();
        long now = System.currentTimeMillis();

        Cursor c = db.query(TABLE_STATUSES, null, COL_STATUS_EXPIRES_AT_MS + " > ?",
                new String[]{String.valueOf(now)}, null, null, COL_STATUS_TIMESTAMP_MS + " ASC");

        if (c != null) {
            try {
                while (c.moveToNext()) {
                    StatusController.StatusSegment seg = cursorToStatusSegment(c);
                    if (seg != null) {
                        seg.viewers = getStatusViewers(seg.id);
                        list.add(seg);
                    }
                }
            } finally {
                c.close();
            }
        }
        return list;
    }

    public synchronized StatusController.StatusSegment getStatusSegmentById(String statusId) {
        if (statusId == null) return null;
        SQLiteDatabase db = getEncryptedDb();
        Cursor c = db.query(TABLE_STATUSES, null, COL_STATUS_ID + " = ?", new String[]{statusId}, null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    StatusController.StatusSegment seg = cursorToStatusSegment(c);
                    if (seg != null) {
                        seg.viewers = getStatusViewers(seg.id);
                        return seg;
                    }
                }
            } finally {
                c.close();
            }
        }
        return null;
    }

    private StatusController.StatusSegment cursorToStatusSegment(Cursor c) {
        try {
            int colId = c.getColumnIndex(COL_STATUS_ID);
            int colAuthorId = c.getColumnIndex(COL_STATUS_AUTHOR_ID);
            int colAuthorName = c.getColumnIndex(COL_STATUS_AUTHOR_NAME);
            int colText = c.getColumnIndex(COL_STATUS_CONTENT_TEXT);
            int colType = c.getColumnIndex(COL_STATUS_MEDIA_TYPE);
            int colMPath = c.getColumnIndex(COL_STATUS_MEDIA_FILE_PATH);
            int colEPath = c.getColumnIndex(COL_STATUS_ENCRYPTED_FILE_PATH);
            int colThumb = c.getColumnIndex(COL_STATUS_THUMB_BLOB);
            int colBg = c.getColumnIndex(COL_STATUS_BG_COLOR_HEX);
            int colFont = c.getColumnIndex(COL_STATUS_FONT_STYLE_INDEX);
            int colTs = c.getColumnIndex(COL_STATUS_TIMESTAMP_MS);
            int colExp = c.getColumnIndex(COL_STATUS_EXPIRES_AT_MS);
            int colBytes = c.getColumnIndex(COL_STATUS_TOTAL_BYTES);
            int colChunks = c.getColumnIndex(COL_STATUS_TOTAL_CHUNKS);
            int colAcl = c.getColumnIndex(COL_STATUS_PRIVACY_ACL_MODE);
            int colMine = c.getColumnIndex(COL_STATUS_IS_MINE);
            int colViewed = c.getColumnIndex(COL_STATUS_IS_VIEWED);
            int colCount = c.getColumnIndex(COL_STATUS_VIEW_COUNT);
            int colKey = c.getColumnIndex(COL_STATUS_AES_KEY_WRAPPED);
            int colIv = c.getColumnIndex(COL_STATUS_IV_BASE64);

            StatusController.StatusSegment seg = new StatusController.StatusSegment();
            seg.id = colId != -1 ? c.getString(colId) : "";
            seg.authorId = colAuthorId != -1 ? c.getString(colAuthorId) : "";
            seg.authorName = colAuthorName != -1 ? c.getString(colAuthorName) : "";
            seg.contentText = colText != -1 ? c.getString(colText) : "";
            String typeStr = colType != -1 ? c.getString(colType) : "text";
            seg.mediaType = StatusController.MediaType.fromWire(typeStr);
            seg.mediaFilePath = colMPath != -1 ? c.getString(colMPath) : null;
            seg.encryptedFilePath = colEPath != -1 ? c.getString(colEPath) : null;
            seg.thumbBlob = colThumb != -1 ? c.getBlob(colThumb) : null;
            seg.bgColorHex = colBg != -1 ? c.getString(colBg) : "#008069";
            seg.fontStyleIndex = colFont != -1 ? c.getInt(colFont) : 0;
            seg.timestampMs = colTs != -1 ? c.getLong(colTs) : System.currentTimeMillis();
            seg.expiresAtMs = colExp != -1 ? c.getLong(colExp) : (seg.timestampMs + StatusController.STATUS_TTL_MS);
            seg.totalBytes = colBytes != -1 ? c.getLong(colBytes) : 0L;
            seg.totalChunks = colChunks != -1 ? c.getInt(colChunks) : 0;
            int aclCode = colAcl != -1 ? c.getInt(colAcl) : 0;
            seg.privacyAcl = StatusController.PrivacyAcl.fromCode(aclCode);
            seg.isMine = colMine != -1 && c.getInt(colMine) == 1;
            seg.isViewed = colViewed != -1 && c.getInt(colViewed) == 1;
            seg.viewCount = colCount != -1 ? c.getInt(colCount) : 0;
            seg.aesKeyWrapped = colKey != -1 ? c.getString(colKey) : null;
            seg.ivBase64 = colIv != -1 ? c.getString(colIv) : null;
            return seg;
        } catch (Exception e) {
            Log.e(TAG, "Error parsing status segment row: " + e.getMessage());
            return null;
        }
    }

    public synchronized void deleteStatusSegment(String statusId) {
        if (statusId == null) return;
        SQLiteDatabase db = getEncryptedDb();
        db.delete(TABLE_STATUS_VIEWS, COL_SV_STATUS_ID + " = ?", new String[]{statusId});
        db.delete(TABLE_STATUS_CHUNKS, COL_SC_STATUS_ID + " = ?", new String[]{statusId});
        db.delete(TABLE_STATUS_GOSSIP_QUEUE, COL_SGQ_STATUS_ID + " = ?", new String[]{statusId});
        db.delete(TABLE_STATUSES, COL_STATUS_ID + " = ?", new String[]{statusId});
        notifyStatusesChanged();
    }

    public synchronized void saveStatusView(String statusId, StatusController.StatusViewer viewer) {
        if (statusId == null || viewer == null || viewer.userId == null) return;
        SQLiteDatabase db = getEncryptedDb();

        ContentValues cv = new ContentValues();
        cv.put(COL_SV_VIEW_ID, UUID.randomUUID().toString());
        cv.put(COL_SV_STATUS_ID, statusId);
        cv.put(COL_SV_VIEWER_ID, viewer.userId);
        cv.put(COL_SV_VIEWER_NAME, viewer.displayName);
        cv.put(COL_SV_VIEWER_AVATAR_URL, viewer.avatarUrl);
        cv.put(COL_SV_VIEWED_AT_MS, viewer.viewedAtMs);
        cv.put(COL_SV_SIGNED_RECEIPT_BLOB, viewer.signedReceiptBlob);

        db.insertWithOnConflict(TABLE_STATUS_VIEWS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        db.execSQL("UPDATE " + TABLE_STATUSES + " SET " + COL_STATUS_VIEW_COUNT + " = " + COL_STATUS_VIEW_COUNT + " + 1 WHERE " + COL_STATUS_ID + " = ?", new String[]{statusId});
        notifyStatusesChanged();
    }

    public synchronized List<StatusController.StatusViewer> getStatusViewers(String statusId) {
        List<StatusController.StatusViewer> list = new ArrayList<>();
        if (statusId == null) return list;
        SQLiteDatabase db = getEncryptedDb();

        Cursor c = db.query(TABLE_STATUS_VIEWS, null, COL_SV_STATUS_ID + " = ?",
                new String[]{statusId}, null, null, COL_SV_VIEWED_AT_MS + " DESC");

        if (c != null) {
            try {
                int colUid = c.getColumnIndex(COL_SV_VIEWER_ID);
                int colName = c.getColumnIndex(COL_SV_VIEWER_NAME);
                int colAvatar = c.getColumnIndex(COL_SV_VIEWER_AVATAR_URL);
                int colTime = c.getColumnIndex(COL_SV_VIEWED_AT_MS);
                int colBlob = c.getColumnIndex(COL_SV_SIGNED_RECEIPT_BLOB);

                while (c.moveToNext()) {
                    String uid = colUid != -1 ? c.getString(colUid) : "";
                    String name = colName != -1 ? c.getString(colName) : "";
                    String avatar = colAvatar != -1 ? c.getString(colAvatar) : null;
                    long time = colTime != -1 ? c.getLong(colTime) : System.currentTimeMillis();
                    byte[] sig = colBlob != -1 ? c.getBlob(colBlob) : null;

                    StatusController.StatusViewer v = new StatusController.StatusViewer(uid, name, avatar, time);
                    v.signedReceiptBlob = sig;
                    list.add(v);
                }
            } finally {
                c.close();
            }
        }
        return list;
    }

    public synchronized void saveStatusChunkBitmap(String statusId, BitSet bitSet, int completedChunks, int totalChunks) {
        if (statusId == null) return;
        SQLiteDatabase db = getEncryptedDb();

        ContentValues cv = new ContentValues();
        cv.put(COL_SC_STATUS_ID, statusId);
        cv.put(COL_SC_CHUNK_BITMAP_BLOB, bitSet != null ? bitSet.toByteArray() : new byte[0]);
        cv.put(COL_SC_COMPLETED_CHUNKS, completedChunks);
        cv.put(COL_SC_TOTAL_CHUNKS, totalChunks);
        cv.put(COL_SC_UPDATED_AT_MS, System.currentTimeMillis());

        db.insertWithOnConflict(TABLE_STATUS_CHUNKS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void saveFileChunkBitmap(String fileId, BitSet bitSet, int completedChunks, int totalChunks) {
        saveStatusChunkBitmap(fileId, bitSet, completedChunks, totalChunks);
    }

    public synchronized BitSet getFileChunkBitmap(String fileId) {
        return getStatusChunkBitmap(fileId);
    }

    public synchronized BitSet getStatusChunkBitmap(String statusId) {
        if (statusId == null) return new BitSet();
        SQLiteDatabase db = getEncryptedDb();
        Cursor c = db.query(TABLE_STATUS_CHUNKS, new String[]{COL_SC_CHUNK_BITMAP_BLOB},
                COL_SC_STATUS_ID + " = ?", new String[]{statusId}, null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    byte[] blob = c.getBlob(0);
                    if (blob != null && blob.length > 0) {
                        return BitSet.valueOf(blob);
                    }
                }
            } finally {
                c.close();
            }
        }
        return new BitSet();
    }

    public synchronized void enqueueStatusGossip(String statusId, String targetPeerId, byte[] payload) {
        if (statusId == null || payload == null) return;
        SQLiteDatabase db = getEncryptedDb();

        ContentValues cv = new ContentValues();
        cv.put(COL_SGQ_QUEUE_ID, UUID.randomUUID().toString());
        cv.put(COL_SGQ_STATUS_ID, statusId);
        cv.put(COL_SGQ_TARGET_PEER_ID, targetPeerId != null ? targetPeerId : "");
        cv.put(COL_SGQ_PAYLOAD_BLOB, payload);
        cv.put(COL_SGQ_RETRY_COUNT, 0);
        cv.put(COL_SGQ_LAST_ATTEMPT_MS, System.currentTimeMillis());

        db.insertWithOnConflict(TABLE_STATUS_GOSSIP_QUEUE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized List<StatusGossipQueueItem> getPendingStatusGossipQueue() {
        List<StatusGossipQueueItem> list = new ArrayList<>();
        SQLiteDatabase db = getEncryptedDb();
        Cursor c = db.query(TABLE_STATUS_GOSSIP_QUEUE, null, null, null, null, null, COL_SGQ_LAST_ATTEMPT_MS + " ASC LIMIT 50");
        if (c != null) {
            try {
                int colQid = c.getColumnIndex(COL_SGQ_QUEUE_ID);
                int colSid = c.getColumnIndex(COL_SGQ_STATUS_ID);
                int colTid = c.getColumnIndex(COL_SGQ_TARGET_PEER_ID);
                int colPayload = c.getColumnIndex(COL_SGQ_PAYLOAD_BLOB);
                int colRetries = c.getColumnIndex(COL_SGQ_RETRY_COUNT);
                int colLast = c.getColumnIndex(COL_SGQ_LAST_ATTEMPT_MS);

                while (c.moveToNext()) {
                    StatusGossipQueueItem item = new StatusGossipQueueItem();
                    item.queueId = colQid != -1 ? c.getString(colQid) : "";
                    item.statusId = colSid != -1 ? c.getString(colSid) : "";
                    item.targetPeerId = colTid != -1 ? c.getString(colTid) : "";
                    item.payloadBlob = colPayload != -1 ? c.getBlob(colPayload) : new byte[0];
                    item.retryCount = colRetries != -1 ? c.getInt(colRetries) : 0;
                    item.lastAttemptMs = colLast != -1 ? c.getLong(colLast) : 0L;
                    list.add(item);
                }
            } finally {
                c.close();
            }
        }
        return list;
    }

    public synchronized void removeStatusGossipQueueItem(String queueId) {
        if (queueId == null) return;
        SQLiteDatabase db = getEncryptedDb();
        db.delete(TABLE_STATUS_GOSSIP_QUEUE, COL_SGQ_QUEUE_ID + " = ?", new String[]{queueId});
    }

    public synchronized List<String> purgeExpiredStatuses(long nowMs) {
        List<String> filesToShred = new ArrayList<>();
        SQLiteDatabase db = getEncryptedDb();

        Cursor c = db.query(TABLE_STATUSES, new String[]{COL_STATUS_ID, COL_STATUS_MEDIA_FILE_PATH, COL_STATUS_ENCRYPTED_FILE_PATH},
                COL_STATUS_EXPIRES_AT_MS + " <= ?", new String[]{String.valueOf(nowMs)}, null, null, null);
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    String mPath = c.getString(1);
                    String ePath = c.getString(2);
                    if (mPath != null && !mPath.isEmpty()) filesToShred.add(mPath);
                    if (ePath != null && !ePath.isEmpty()) filesToShred.add(ePath);
                }
            } finally {
                c.close();
            }
        }

        db.delete(TABLE_STATUS_VIEWS, COL_SV_STATUS_ID + " IN (SELECT " + COL_STATUS_ID + " FROM " + TABLE_STATUSES + " WHERE " + COL_STATUS_EXPIRES_AT_MS + " <= ?)", new String[]{String.valueOf(nowMs)});
        db.delete(TABLE_STATUS_CHUNKS, COL_SC_STATUS_ID + " IN (SELECT " + COL_STATUS_ID + " FROM " + TABLE_STATUSES + " WHERE " + COL_STATUS_EXPIRES_AT_MS + " <= ?)", new String[]{String.valueOf(nowMs)});
        db.delete(TABLE_STATUS_GOSSIP_QUEUE, COL_SGQ_STATUS_ID + " IN (SELECT " + COL_STATUS_ID + " FROM " + TABLE_STATUSES + " WHERE " + COL_STATUS_EXPIRES_AT_MS + " <= ?)", new String[]{String.valueOf(nowMs)});
        int count = db.delete(TABLE_STATUSES, COL_STATUS_EXPIRES_AT_MS + " <= ?", new String[]{String.valueOf(nowMs)});
        if (count > 0) {
            notifyStatusesChanged();
        }
        return filesToShred;
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // SCHEDULED MESSAGES & CHANNEL POSTS (#80, #83)
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    public static class ScheduledMessage {
        public String id;
        public String dialogId;
        public String senderId;
        public String senderName;
        public String text;
        public String mediaUrl;
        public String localPath;
        public String msgType;
        public long scheduledAt;
        public long expiresAt;
        public String status; // "PENDING", "SENT", "CANCELLED", "FAILED"
        public boolean isChannelBroadcast;
        public long createdAt;

        public ScheduledMessage() {}

        public ScheduledMessage(String id, String dialogId, String senderId, String senderName,
                                String text, String mediaUrl, String localPath, String msgType,
                                long scheduledAt, long expiresAt, boolean isChannelBroadcast) {
            this.id = id != null ? id : UUID.randomUUID().toString();
            this.dialogId = dialogId;
            this.senderId = senderId;
            this.senderName = senderName;
            this.text = text;
            this.mediaUrl = mediaUrl;
            this.localPath = localPath;
            this.msgType = msgType != null ? msgType : "text";
            this.scheduledAt = scheduledAt;
            this.expiresAt = expiresAt;
            this.status = "PENDING";
            this.isChannelBroadcast = isChannelBroadcast;
            this.createdAt = System.currentTimeMillis();
        }
    }

    public synchronized void saveScheduledMessage(ScheduledMessage msg) {
        if (msg == null || msg.id == null) return;
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(COL_SCHED_ID, msg.id);
        cv.put(COL_SCHED_DIALOG_ID, msg.dialogId);
        cv.put(COL_SCHED_SENDER_ID, msg.senderId);
        cv.put(COL_SCHED_SENDER_NAME, msg.senderName);
        cv.put(COL_SCHED_TEXT, msg.text);
        cv.put(COL_SCHED_MEDIA_URL, msg.mediaUrl);
        cv.put(COL_SCHED_LOCAL_PATH, msg.localPath);
        cv.put(COL_SCHED_MSG_TYPE, msg.msgType);
        cv.put(COL_SCHED_SCHEDULED_AT, msg.scheduledAt);
        cv.put(COL_SCHED_EXPIRES_AT, msg.expiresAt);
        cv.put(COL_SCHED_STATUS, msg.status != null ? msg.status : "PENDING");
        cv.put(COL_SCHED_IS_CHANNEL, msg.isChannelBroadcast ? 1 : 0);
        cv.put(COL_SCHED_CREATED_AT, msg.createdAt > 0 ? msg.createdAt : System.currentTimeMillis());

        db.insertWithOnConflict(TABLE_SCHEDULED_MESSAGES, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized List<ScheduledMessage> getPendingScheduledMessages(long dueBeforeMillis) {
        List<ScheduledMessage> list = new ArrayList<>();
        SQLiteDatabase db = getEncryptedDb();
        Cursor c = db.query(TABLE_SCHEDULED_MESSAGES, null,
                COL_SCHED_STATUS + " = 'PENDING' AND " + COL_SCHED_SCHEDULED_AT + " <= ?",
                new String[]{String.valueOf(dueBeforeMillis)}, null, null, COL_SCHED_SCHEDULED_AT + " ASC");
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    ScheduledMessage m = new ScheduledMessage();
                    m.id = c.getString(c.getColumnIndexOrThrow(COL_SCHED_ID));
                    m.dialogId = c.getString(c.getColumnIndexOrThrow(COL_SCHED_DIALOG_ID));
                    m.senderId = c.getString(c.getColumnIndexOrThrow(COL_SCHED_SENDER_ID));
                    m.senderName = c.getString(c.getColumnIndexOrThrow(COL_SCHED_SENDER_NAME));
                    m.text = c.getString(c.getColumnIndexOrThrow(COL_SCHED_TEXT));
                    m.mediaUrl = c.getString(c.getColumnIndexOrThrow(COL_SCHED_MEDIA_URL));
                    m.localPath = c.getString(c.getColumnIndexOrThrow(COL_SCHED_LOCAL_PATH));
                    m.msgType = c.getString(c.getColumnIndexOrThrow(COL_SCHED_MSG_TYPE));
                    m.scheduledAt = c.getLong(c.getColumnIndexOrThrow(COL_SCHED_SCHEDULED_AT));
                    m.expiresAt = c.getLong(c.getColumnIndexOrThrow(COL_SCHED_EXPIRES_AT));
                    m.status = c.getString(c.getColumnIndexOrThrow(COL_SCHED_STATUS));
                    m.isChannelBroadcast = c.getInt(c.getColumnIndexOrThrow(COL_SCHED_IS_CHANNEL)) == 1;
                    m.createdAt = c.getLong(c.getColumnIndexOrThrow(COL_SCHED_CREATED_AT));
                    list.add(m);
                }
            } finally {
                c.close();
            }
        }
        return list;
    }

    public synchronized List<ScheduledMessage> getScheduledMessagesForDialog(String dialogId) {
        List<ScheduledMessage> list = new ArrayList<>();
        if (dialogId == null) return list;
        SQLiteDatabase db = getEncryptedDb();
        Cursor c = db.query(TABLE_SCHEDULED_MESSAGES, null,
                COL_SCHED_DIALOG_ID + " = ? AND " + COL_SCHED_STATUS + " = 'PENDING'",
                new String[]{dialogId}, null, null, COL_SCHED_SCHEDULED_AT + " ASC");
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    ScheduledMessage m = new ScheduledMessage();
                    m.id = c.getString(c.getColumnIndexOrThrow(COL_SCHED_ID));
                    m.dialogId = c.getString(c.getColumnIndexOrThrow(COL_SCHED_DIALOG_ID));
                    m.senderId = c.getString(c.getColumnIndexOrThrow(COL_SCHED_SENDER_ID));
                    m.senderName = c.getString(c.getColumnIndexOrThrow(COL_SCHED_SENDER_NAME));
                    m.text = c.getString(c.getColumnIndexOrThrow(COL_SCHED_TEXT));
                    m.mediaUrl = c.getString(c.getColumnIndexOrThrow(COL_SCHED_MEDIA_URL));
                    m.localPath = c.getString(c.getColumnIndexOrThrow(COL_SCHED_LOCAL_PATH));
                    m.msgType = c.getString(c.getColumnIndexOrThrow(COL_SCHED_MSG_TYPE));
                    m.scheduledAt = c.getLong(c.getColumnIndexOrThrow(COL_SCHED_SCHEDULED_AT));
                    m.expiresAt = c.getLong(c.getColumnIndexOrThrow(COL_SCHED_EXPIRES_AT));
                    m.status = c.getString(c.getColumnIndexOrThrow(COL_SCHED_STATUS));
                    m.isChannelBroadcast = c.getInt(c.getColumnIndexOrThrow(COL_SCHED_IS_CHANNEL)) == 1;
                    m.createdAt = c.getLong(c.getColumnIndexOrThrow(COL_SCHED_CREATED_AT));
                    list.add(m);
                }
            } finally {
                c.close();
            }
        }
        return list;
    }

    public synchronized void updateScheduledMessageStatus(String id, String status) {
        if (id == null) return;
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(COL_SCHED_STATUS, status);
        db.update(TABLE_SCHEDULED_MESSAGES, cv, COL_SCHED_ID + " = ?", new String[]{id});
    }

    public synchronized void deleteScheduledMessage(String id) {
        if (id == null) return;
        SQLiteDatabase db = getEncryptedDb();
        db.delete(TABLE_SCHEDULED_MESSAGES, COL_SCHED_ID + " = ?", new String[]{id});
    }

    public synchronized void saveSovereignProfileManifest(com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest manifest) {
        if (manifest == null || manifest.uid == null || manifest.uid.isEmpty()) return;
        SQLiteDatabase db = getEncryptedDb();
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_SOVEREIGN_MANIFEST + " (" +
                COL_SM_UID + " TEXT PRIMARY KEY, " +
                COL_SM_USERNAME + " TEXT, " +
                COL_SM_DISPLAY_NAME + " TEXT, " +
                COL_SM_BIO + " TEXT, " +
                COL_SM_AVATAR_BASE64 + " TEXT, " +
                COL_SM_MOOD + " TEXT, " +
                COL_SM_XP + " INTEGER, " +
                COL_SM_KARMA + " INTEGER, " +
                COL_SM_VEIL_COINS + " INTEGER, " +
                COL_SM_PERSONAS_JSON + " TEXT, " +
                COL_SM_PUBLIC_KEY + " TEXT, " +
                COL_SM_UPDATED_AT + " INTEGER, " +
                COL_SM_SIGNATURE + " TEXT" +
                ");");
        ContentValues cv = new ContentValues();
        cv.put(COL_SM_UID, manifest.uid);
        cv.put(COL_SM_USERNAME, manifest.username);
        cv.put(COL_SM_DISPLAY_NAME, manifest.displayName);
        cv.put(COL_SM_BIO, manifest.bio);
        cv.put(COL_SM_AVATAR_BASE64, manifest.avatarBase64);
        cv.put(COL_SM_MOOD, manifest.mood);
        cv.put(COL_SM_XP, manifest.xp);
        cv.put(COL_SM_KARMA, manifest.karma);
        cv.put(COL_SM_VEIL_COINS, manifest.veilCoins);
        cv.put(COL_SM_PERSONAS_JSON, manifest.personasJsonArray);
        cv.put(COL_SM_PUBLIC_KEY, manifest.publicKeyBase64);
        cv.put(COL_SM_UPDATED_AT, manifest.updatedAtTimestamp);
        cv.put(COL_SM_SIGNATURE, manifest.signature);
        db.insertWithOnConflict(TABLE_SOVEREIGN_MANIFEST, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest getSovereignProfileManifest(String uid) {
        if (uid == null || uid.isEmpty()) return null;
        SQLiteDatabase db = getEncryptedDb();
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_SOVEREIGN_MANIFEST + " (" +
                COL_SM_UID + " TEXT PRIMARY KEY, " +
                COL_SM_USERNAME + " TEXT, " +
                COL_SM_DISPLAY_NAME + " TEXT, " +
                COL_SM_BIO + " TEXT, " +
                COL_SM_AVATAR_BASE64 + " TEXT, " +
                COL_SM_MOOD + " TEXT, " +
                COL_SM_XP + " INTEGER, " +
                COL_SM_KARMA + " INTEGER, " +
                COL_SM_VEIL_COINS + " INTEGER, " +
                COL_SM_PERSONAS_JSON + " TEXT, " +
                COL_SM_PUBLIC_KEY + " TEXT, " +
                COL_SM_UPDATED_AT + " INTEGER, " +
                COL_SM_SIGNATURE + " TEXT" +
                ");");
        Cursor c = db.query(TABLE_SOVEREIGN_MANIFEST, null, COL_SM_UID + " = ?", new String[]{uid}, null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest m =
                            new com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest();
                    m.uid = c.getString(c.getColumnIndexOrThrow(COL_SM_UID));
                    m.username = c.getString(c.getColumnIndexOrThrow(COL_SM_USERNAME));
                    m.displayName = c.getString(c.getColumnIndexOrThrow(COL_SM_DISPLAY_NAME));
                    m.bio = c.getString(c.getColumnIndexOrThrow(COL_SM_BIO));
                    m.avatarBase64 = c.getString(c.getColumnIndexOrThrow(COL_SM_AVATAR_BASE64));
                    m.mood = c.getString(c.getColumnIndexOrThrow(COL_SM_MOOD));
                    m.xp = c.getLong(c.getColumnIndexOrThrow(COL_SM_XP));
                    m.karma = c.getLong(c.getColumnIndexOrThrow(COL_SM_KARMA));
                    m.veilCoins = c.getLong(c.getColumnIndexOrThrow(COL_SM_VEIL_COINS));
                    m.personasJsonArray = c.getString(c.getColumnIndexOrThrow(COL_SM_PERSONAS_JSON));
                    m.publicKeyBase64 = c.getString(c.getColumnIndexOrThrow(COL_SM_PUBLIC_KEY));
                    m.updatedAtTimestamp = c.getLong(c.getColumnIndexOrThrow(COL_SM_UPDATED_AT));
                    m.signature = c.getString(c.getColumnIndexOrThrow(COL_SM_SIGNATURE));
                    return m;
                }
            } finally {
                c.close();
            }
        }
        return null;
    }

    public synchronized List<com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest> searchSovereignProfileManifests(String query) {
        List<com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest> list = new ArrayList<>();
        if (query == null) return list;
        SQLiteDatabase db = getEncryptedDb();
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_SOVEREIGN_MANIFEST + " (" +
                COL_SM_UID + " TEXT PRIMARY KEY, " +
                COL_SM_USERNAME + " TEXT, " +
                COL_SM_DISPLAY_NAME + " TEXT, " +
                COL_SM_BIO + " TEXT, " +
                COL_SM_AVATAR_BASE64 + " TEXT, " +
                COL_SM_MOOD + " TEXT, " +
                COL_SM_XP + " INTEGER, " +
                COL_SM_KARMA + " INTEGER, " +
                COL_SM_VEIL_COINS + " INTEGER, " +
                COL_SM_PERSONAS_JSON + " TEXT, " +
                COL_SM_PUBLIC_KEY + " TEXT, " +
                COL_SM_UPDATED_AT + " INTEGER, " +
                COL_SM_SIGNATURE + " TEXT" +
                ");");
        String q = "%" + query.toLowerCase(java.util.Locale.ROOT) + "%";
        Cursor cursor = db.query(TABLE_SOVEREIGN_MANIFEST, null,
                "LOWER(" + COL_SM_USERNAME + ") LIKE ? OR LOWER(" + COL_SM_DISPLAY_NAME + ") LIKE ? OR LOWER(" + COL_SM_UID + ") LIKE ?",
                new String[]{q, q, q}, null, null, COL_SM_UPDATED_AT + " DESC LIMIT 30");
        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest m =
                            new com.m3.veil.utils.SovereignIdentityAndDirectoryEngine.SovereignProfileManifest();
                    m.uid = cursor.getString(cursor.getColumnIndexOrThrow(COL_SM_UID));
                    m.username = cursor.getString(cursor.getColumnIndexOrThrow(COL_SM_USERNAME));
                    m.displayName = cursor.getString(cursor.getColumnIndexOrThrow(COL_SM_DISPLAY_NAME));
                    m.bio = cursor.getString(cursor.getColumnIndexOrThrow(COL_SM_BIO));
                    m.avatarBase64 = cursor.getString(cursor.getColumnIndexOrThrow(COL_SM_AVATAR_BASE64));
                    m.mood = cursor.getString(cursor.getColumnIndexOrThrow(COL_SM_MOOD));
                    m.xp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_SM_XP));
                    m.karma = cursor.getLong(cursor.getColumnIndexOrThrow(COL_SM_KARMA));
                    m.veilCoins = cursor.getLong(cursor.getColumnIndexOrThrow(COL_SM_VEIL_COINS));
                    m.personasJsonArray = cursor.getString(cursor.getColumnIndexOrThrow(COL_SM_PERSONAS_JSON));
                    m.publicKeyBase64 = cursor.getString(cursor.getColumnIndexOrThrow(COL_SM_PUBLIC_KEY));
                    m.updatedAtTimestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_SM_UPDATED_AT));
                    m.signature = cursor.getString(cursor.getColumnIndexOrThrow(COL_SM_SIGNATURE));
                    list.add(m);
                }
            } finally {
                cursor.close();
            }
        }
        return list;
    }

    public synchronized int reapExpiredChannelPosts(long now) {
        SQLiteDatabase db = getEncryptedDb();
        int count = db.delete(TABLE_MESSAGES, COL_MSG_EXPIRES_AT + " > 0 AND " + COL_MSG_EXPIRES_AT + " <= ?", new String[]{String.valueOf(now)});
        if (count > 0) {
            notifyChatsChanged();
        }
        return count;
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // READ-LATER REMINDERS (#178)
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    public static class ReadLaterReminder {
        public String id;
        public String messageId;
        public String dialogId;
        public String title;
        public String snippet;
        public long remindAt;
        public long createdAt;
        public String status; // "PENDING", "TRIGGERED", "DISMISSED"

        public ReadLaterReminder() {}

        public ReadLaterReminder(String id, String messageId, String dialogId, String title, String snippet, long remindAt) {
            this.id = id != null ? id : UUID.randomUUID().toString();
            this.messageId = messageId;
            this.dialogId = dialogId;
            this.title = title;
            this.snippet = snippet;
            this.remindAt = remindAt;
            this.createdAt = System.currentTimeMillis();
            this.status = "PENDING";
        }
    }

    public synchronized void saveReminder(ReadLaterReminder r) {
        if (r == null || r.id == null) return;
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(COL_REM_ID, r.id);
        cv.put(COL_REM_MESSAGE_ID, r.messageId);
        cv.put(COL_REM_DIALOG_ID, r.dialogId);
        cv.put(COL_REM_TITLE, r.title);
        cv.put(COL_REM_SNIPPET, r.snippet);
        cv.put(COL_REM_REMIND_AT, r.remindAt);
        cv.put(COL_REM_CREATED_AT, r.createdAt > 0 ? r.createdAt : System.currentTimeMillis());
        cv.put(COL_REM_STATUS, r.status != null ? r.status : "PENDING");
        db.insertWithOnConflict(TABLE_REMINDERS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized List<ReadLaterReminder> getPendingReminders(long dueBeforeMillis) {
        List<ReadLaterReminder> list = new ArrayList<>();
        SQLiteDatabase db = getEncryptedDb();
        Cursor c = db.query(TABLE_REMINDERS, null,
                COL_REM_STATUS + " = 'PENDING' AND " + COL_REM_REMIND_AT + " <= ?",
                new String[]{String.valueOf(dueBeforeMillis)}, null, null, COL_REM_REMIND_AT + " ASC");
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    ReadLaterReminder r = new ReadLaterReminder();
                    r.id = c.getString(c.getColumnIndexOrThrow(COL_REM_ID));
                    r.messageId = c.getString(c.getColumnIndexOrThrow(COL_REM_MESSAGE_ID));
                    r.dialogId = c.getString(c.getColumnIndexOrThrow(COL_REM_DIALOG_ID));
                    r.title = c.getString(c.getColumnIndexOrThrow(COL_REM_TITLE));
                    r.snippet = c.getString(c.getColumnIndexOrThrow(COL_REM_SNIPPET));
                    r.remindAt = c.getLong(c.getColumnIndexOrThrow(COL_REM_REMIND_AT));
                    r.createdAt = c.getLong(c.getColumnIndexOrThrow(COL_REM_CREATED_AT));
                    r.status = c.getString(c.getColumnIndexOrThrow(COL_REM_STATUS));
                    list.add(r);
                }
            } finally {
                c.close();
            }
        }
        return list;
    }

    public synchronized void updateReminderStatus(String id, String status) {
        if (id == null) return;
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(COL_REM_STATUS, status);
        db.update(TABLE_REMINDERS, cv, COL_REM_ID + " = ?", new String[]{id});
    }

    public synchronized void deleteReminder(String id) {
        if (id == null) return;
        SQLiteDatabase db = getEncryptedDb();
        db.delete(TABLE_REMINDERS, COL_REM_ID + " = ?", new String[]{id});
    }

    public synchronized List<VaultNote> getPendingFutureSelfNotes(long now) {
        List<VaultNote> notes = new ArrayList<>();
        SQLiteDatabase db = getEncryptedDb();
        Cursor c = db.query(TABLE_NOTES, null,
                COL_NOTE_TYPE + " = 'future_self' AND " + COL_NOTE_UNLOCK_AT + " > 0 AND " + COL_NOTE_UNLOCK_AT + " <= ?",
                new String[]{String.valueOf(now)}, null, null, COL_NOTE_UNLOCK_AT + " ASC");
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    notes.add(new VaultNote(
                            c.getString(c.getColumnIndexOrThrow(COL_NOTE_ID)),
                            c.getString(c.getColumnIndexOrThrow(COL_NOTE_TITLE)),
                            c.getString(c.getColumnIndexOrThrow(COL_NOTE_CONTENT)),
                            c.getString(c.getColumnIndexOrThrow(COL_NOTE_TYPE)),
                            c.getLong(c.getColumnIndexOrThrow(COL_NOTE_TIMESTAMP)),
                            c.getLong(c.getColumnIndexOrThrow(COL_NOTE_UNLOCK_AT))
                    ));
                }
            } finally {
                c.close();
            }
        }
        return notes;
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // GROUP SUBGROUPS / TOPICS (#71)
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    public static class GroupSubgroup {
        public String subgroupId;
        public String parentGroupId;
        public String title;
        public String description;
        public String inviteKey;
        public String roleRequired;
        public boolean isSecret;
        public long createdAt;

        public GroupSubgroup() {}

        public GroupSubgroup(String subgroupId, String parentGroupId, String title, String description,
                             String inviteKey, String roleRequired, boolean isSecret) {
            this.subgroupId = subgroupId != null ? subgroupId : UUID.randomUUID().toString();
            this.parentGroupId = parentGroupId;
            this.title = title;
            this.description = description;
            this.inviteKey = inviteKey;
            this.roleRequired = roleRequired != null ? roleRequired : "MEMBER";
            this.isSecret = isSecret;
            this.createdAt = System.currentTimeMillis();
        }
    }

    public synchronized void saveSubgroup(GroupSubgroup sg) {
        if (sg == null || sg.subgroupId == null) return;
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(COL_SUB_ID, sg.subgroupId);
        cv.put(COL_SUB_PARENT_GROUP_ID, sg.parentGroupId);
        cv.put(COL_SUB_TITLE, sg.title);
        cv.put(COL_SUB_DESCRIPTION, sg.description);
        cv.put(COL_SUB_INVITE_KEY, sg.inviteKey);
        cv.put(COL_SUB_ROLE_REQUIRED, sg.roleRequired);
        cv.put(COL_SUB_IS_SECRET, sg.isSecret ? 1 : 0);
        cv.put(COL_SUB_CREATED_AT, sg.createdAt > 0 ? sg.createdAt : System.currentTimeMillis());
        db.insertWithOnConflict(TABLE_GROUP_SUBGROUPS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        notifyChatsChanged();
    }

    public synchronized List<GroupSubgroup> getSubgroupsForGroup(String parentGroupId) {
        List<GroupSubgroup> list = new ArrayList<>();
        if (parentGroupId == null) return list;
        SQLiteDatabase db = getEncryptedDb();
        Cursor c = db.query(TABLE_GROUP_SUBGROUPS, null, COL_SUB_PARENT_GROUP_ID + " = ?",
                new String[]{parentGroupId}, null, null, COL_SUB_CREATED_AT + " ASC");
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    GroupSubgroup sg = new GroupSubgroup();
                    sg.subgroupId = c.getString(c.getColumnIndexOrThrow(COL_SUB_ID));
                    sg.parentGroupId = c.getString(c.getColumnIndexOrThrow(COL_SUB_PARENT_GROUP_ID));
                    sg.title = c.getString(c.getColumnIndexOrThrow(COL_SUB_TITLE));
                    sg.description = c.getString(c.getColumnIndexOrThrow(COL_SUB_DESCRIPTION));
                    sg.inviteKey = c.getString(c.getColumnIndexOrThrow(COL_SUB_INVITE_KEY));
                    sg.roleRequired = c.getString(c.getColumnIndexOrThrow(COL_SUB_ROLE_REQUIRED));
                    sg.isSecret = c.getInt(c.getColumnIndexOrThrow(COL_SUB_IS_SECRET)) == 1;
                    sg.createdAt = c.getLong(c.getColumnIndexOrThrow(COL_SUB_CREATED_AT));
                    list.add(sg);
                }
            } finally {
                c.close();
            }
        }
        return list;
    }

    public synchronized void deleteSubgroup(String subgroupId) {
        if (subgroupId == null) return;
        SQLiteDatabase db = getEncryptedDb();
        db.delete(TABLE_GROUP_SUBGROUPS, COL_SUB_ID + " = ?", new String[]{subgroupId});
        notifyChatsChanged();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // GROUP EVENTS CALENDAR (#68, #225)
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    public static class GroupEvent {
        public String eventId;
        public String groupId;
        public String creatorId;
        public String creatorName;
        public String title;
        public String description;
        public String locationOrLink;
        public long startTime;
        public long endTime;
        public int rsvpCount;
        public String userRsvpStatus; // "NONE", "GOING", "MAYBE", "DECLINED"
        public boolean countdownActive;
        public long createdAt;

        public GroupEvent() {}

        public GroupEvent(String eventId, String groupId, String creatorId, String creatorName,
                          String title, String description, String locationOrLink,
                          long startTime, long endTime) {
            this.eventId = eventId != null ? eventId : UUID.randomUUID().toString();
            this.groupId = groupId;
            this.creatorId = creatorId;
            this.creatorName = creatorName;
            this.title = title;
            this.description = description;
            this.locationOrLink = locationOrLink;
            this.startTime = startTime;
            this.endTime = endTime;
            this.rsvpCount = 1;
            this.userRsvpStatus = "GOING";
            this.countdownActive = true;
            this.createdAt = System.currentTimeMillis();
        }
    }

    public synchronized void saveGroupEvent(GroupEvent evt) {
        if (evt == null || evt.eventId == null) return;
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(COL_EVT_ID, evt.eventId);
        cv.put(COL_EVT_GROUP_ID, evt.groupId);
        cv.put(COL_EVT_CREATOR_ID, evt.creatorId);
        cv.put(COL_EVT_CREATOR_NAME, evt.creatorName);
        cv.put(COL_EVT_TITLE, evt.title);
        cv.put(COL_EVT_DESCRIPTION, evt.description);
        cv.put(COL_EVT_LOCATION_OR_LINK, evt.locationOrLink);
        cv.put(COL_EVT_START_TIME, evt.startTime);
        cv.put(COL_EVT_END_TIME, evt.endTime);
        cv.put(COL_EVT_RSVP_COUNT, evt.rsvpCount);
        cv.put(COL_EVT_USER_RSVP, evt.userRsvpStatus != null ? evt.userRsvpStatus : "NONE");
        cv.put(COL_EVT_COUNTDOWN_ACTIVE, evt.countdownActive ? 1 : 0);
        cv.put(COL_EVT_CREATED_AT, evt.createdAt > 0 ? evt.createdAt : System.currentTimeMillis());

        db.insertWithOnConflict(TABLE_GROUP_EVENTS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized List<GroupEvent> getEventsForGroup(String groupId) {
        List<GroupEvent> list = new ArrayList<>();
        if (groupId == null) return list;
        SQLiteDatabase db = getEncryptedDb();
        Cursor c = db.query(TABLE_GROUP_EVENTS, null, COL_EVT_GROUP_ID + " = ?",
                new String[]{groupId}, null, null, COL_EVT_START_TIME + " ASC");
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    GroupEvent e = new GroupEvent();
                    e.eventId = c.getString(c.getColumnIndexOrThrow(COL_EVT_ID));
                    e.groupId = c.getString(c.getColumnIndexOrThrow(COL_EVT_GROUP_ID));
                    e.creatorId = c.getString(c.getColumnIndexOrThrow(COL_EVT_CREATOR_ID));
                    e.creatorName = c.getString(c.getColumnIndexOrThrow(COL_EVT_CREATOR_NAME));
                    e.title = c.getString(c.getColumnIndexOrThrow(COL_EVT_TITLE));
                    e.description = c.getString(c.getColumnIndexOrThrow(COL_EVT_DESCRIPTION));
                    e.locationOrLink = c.getString(c.getColumnIndexOrThrow(COL_EVT_LOCATION_OR_LINK));
                    e.startTime = c.getLong(c.getColumnIndexOrThrow(COL_EVT_START_TIME));
                    e.endTime = c.getLong(c.getColumnIndexOrThrow(COL_EVT_END_TIME));
                    e.rsvpCount = c.getInt(c.getColumnIndexOrThrow(COL_EVT_RSVP_COUNT));
                    e.userRsvpStatus = c.getString(c.getColumnIndexOrThrow(COL_EVT_USER_RSVP));
                    e.countdownActive = c.getInt(c.getColumnIndexOrThrow(COL_EVT_COUNTDOWN_ACTIVE)) == 1;
                    e.createdAt = c.getLong(c.getColumnIndexOrThrow(COL_EVT_CREATED_AT));
                    list.add(e);
                }
            } finally {
                c.close();
            }
        }
        return list;
    }

    public synchronized List<GroupEvent> getAllUpcomingEvents() {
        List<GroupEvent> list = new ArrayList<>();
        SQLiteDatabase db = getEncryptedDb();
        Cursor c = db.query(TABLE_GROUP_EVENTS, null, COL_EVT_START_TIME + " >= ?",
                new String[]{String.valueOf(System.currentTimeMillis() - 3600000L)}, null, null, COL_EVT_START_TIME + " ASC");
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    GroupEvent e = new GroupEvent();
                    e.eventId = c.getString(c.getColumnIndexOrThrow(COL_EVT_ID));
                    e.groupId = c.getString(c.getColumnIndexOrThrow(COL_EVT_GROUP_ID));
                    e.creatorId = c.getString(c.getColumnIndexOrThrow(COL_EVT_CREATOR_ID));
                    e.creatorName = c.getString(c.getColumnIndexOrThrow(COL_EVT_CREATOR_NAME));
                    e.title = c.getString(c.getColumnIndexOrThrow(COL_EVT_TITLE));
                    e.description = c.getString(c.getColumnIndexOrThrow(COL_EVT_DESCRIPTION));
                    e.locationOrLink = c.getString(c.getColumnIndexOrThrow(COL_EVT_LOCATION_OR_LINK));
                    e.startTime = c.getLong(c.getColumnIndexOrThrow(COL_EVT_START_TIME));
                    e.endTime = c.getLong(c.getColumnIndexOrThrow(COL_EVT_END_TIME));
                    e.rsvpCount = c.getInt(c.getColumnIndexOrThrow(COL_EVT_RSVP_COUNT));
                    e.userRsvpStatus = c.getString(c.getColumnIndexOrThrow(COL_EVT_USER_RSVP));
                    e.countdownActive = c.getInt(c.getColumnIndexOrThrow(COL_EVT_COUNTDOWN_ACTIVE)) == 1;
                    e.createdAt = c.getLong(c.getColumnIndexOrThrow(COL_EVT_CREATED_AT));
                    list.add(e);
                }
            } finally {
                c.close();
            }
        }
        return list;
    }

    public synchronized void updateEventRsvp(String eventId, String status, int newCount) {
        if (eventId == null) return;
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(COL_EVT_USER_RSVP, status);
        cv.put(COL_EVT_RSVP_COUNT, newCount);
        db.update(TABLE_GROUP_EVENTS, cv, COL_EVT_ID + " = ?", new String[]{eventId});
    }

    public synchronized void deleteGroupEvent(String eventId) {
        if (eventId == null) return;
        SQLiteDatabase db = getEncryptedDb();
        db.delete(TABLE_GROUP_EVENTS, COL_EVT_ID + " = ?", new String[]{eventId});
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // ANONYMOUS CHANNEL ADMIN KEYS (#82)
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    public static class ChannelAdminKey {
        public String channelId;
        public String adminAlias;
        public String blindPublicKey;
        public String blindPrivateKey;
        public int rotationEpoch;
        public long lastRotated;

        public ChannelAdminKey() {}

        public ChannelAdminKey(String channelId, String adminAlias, String blindPublicKey, String blindPrivateKey) {
            this.channelId = channelId;
            this.adminAlias = adminAlias != null ? adminAlias : "Channel Admin";
            this.blindPublicKey = blindPublicKey;
            this.blindPrivateKey = blindPrivateKey;
            this.rotationEpoch = 1;
            this.lastRotated = System.currentTimeMillis();
        }
    }

    public synchronized void saveChannelAdminKey(ChannelAdminKey key) {
        if (key == null || key.channelId == null) return;
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(COL_CAK_CHANNEL_ID, key.channelId);
        cv.put(COL_CAK_ADMIN_ALIAS, key.adminAlias);
        cv.put(COL_CAK_BLIND_PUBLIC_KEY, key.blindPublicKey);
        cv.put(COL_CAK_BLIND_PRIVATE_KEY, key.blindPrivateKey);
        cv.put(COL_CAK_ROTATION_EPOCH, key.rotationEpoch);
        cv.put(COL_CAK_LAST_ROTATED, key.lastRotated > 0 ? key.lastRotated : System.currentTimeMillis());
        db.insertWithOnConflict(TABLE_CHANNEL_ADMIN_KEYS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized ChannelAdminKey getChannelAdminKey(String channelId) {
        if (channelId == null) return null;
        SQLiteDatabase db = getEncryptedDb();
        Cursor c = db.query(TABLE_CHANNEL_ADMIN_KEYS, null, COL_CAK_CHANNEL_ID + " = ?",
                new String[]{channelId}, null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    ChannelAdminKey key = new ChannelAdminKey();
                    key.channelId = c.getString(c.getColumnIndexOrThrow(COL_CAK_CHANNEL_ID));
                    key.adminAlias = c.getString(c.getColumnIndexOrThrow(COL_CAK_ADMIN_ALIAS));
                    key.blindPublicKey = c.getString(c.getColumnIndexOrThrow(COL_CAK_BLIND_PUBLIC_KEY));
                    key.blindPrivateKey = c.getString(c.getColumnIndexOrThrow(COL_CAK_BLIND_PRIVATE_KEY));
                    key.rotationEpoch = c.getInt(c.getColumnIndexOrThrow(COL_CAK_ROTATION_EPOCH));
                    key.lastRotated = c.getLong(c.getColumnIndexOrThrow(COL_CAK_LAST_ROTATED));
                    return key;
                }
            } finally {
                c.close();
            }
        }
        return null;
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // VEILBOARD REPOSTS / QUOTES (#92)
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    public static class VeilBoardRepost {
        public String repostId;
        public String originalPostId;
        public String originalAuthorId;
        public String originalAuthorName;
        public String reposterPersonaId;
        public String reposterName;
        public String quoteText;
        public long timestamp;

        public VeilBoardRepost() {}

        public VeilBoardRepost(String repostId, String originalPostId, String originalAuthorId,
                               String originalAuthorName, String reposterPersonaId, String reposterName,
                               String quoteText) {
            this.repostId = repostId != null ? repostId : UUID.randomUUID().toString();
            this.originalPostId = originalPostId;
            this.originalAuthorId = originalAuthorId;
            this.originalAuthorName = originalAuthorName;
            this.reposterPersonaId = reposterPersonaId;
            this.reposterName = reposterName;
            this.quoteText = quoteText;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public synchronized void saveRepost(VeilBoardRepost rep) {
        if (rep == null || rep.repostId == null) return;
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(COL_REP_ID, rep.repostId);
        cv.put(COL_REP_ORIGINAL_POST_ID, rep.originalPostId);
        cv.put(COL_REP_ORIGINAL_AUTHOR_ID, rep.originalAuthorId);
        cv.put(COL_REP_ORIGINAL_AUTHOR_NAME, rep.originalAuthorName);
        cv.put(COL_REP_REPOSTER_PERSONA_ID, rep.reposterPersonaId);
        cv.put(COL_REP_REPOSTER_NAME, rep.reposterName);
        cv.put(COL_REP_QUOTE_TEXT, rep.quoteText);
        cv.put(COL_REP_TIMESTAMP, rep.timestamp > 0 ? rep.timestamp : System.currentTimeMillis());
        db.insertWithOnConflict(TABLE_REPOSTS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        notifyPostsChanged();
    }

    public synchronized List<VeilBoardRepost> getRepostsForOriginal(String postId) {
        List<VeilBoardRepost> list = new ArrayList<>();
        if (postId == null) return list;
        SQLiteDatabase db = getEncryptedDb();
        Cursor c = db.query(TABLE_REPOSTS, null, COL_REP_ORIGINAL_POST_ID + " = ?",
                new String[]{postId}, null, null, COL_REP_TIMESTAMP + " DESC");
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    VeilBoardRepost r = new VeilBoardRepost();
                    r.repostId = c.getString(c.getColumnIndexOrThrow(COL_REP_ID));
                    r.originalPostId = c.getString(c.getColumnIndexOrThrow(COL_REP_ORIGINAL_POST_ID));
                    r.originalAuthorId = c.getString(c.getColumnIndexOrThrow(COL_REP_ORIGINAL_AUTHOR_ID));
                    r.originalAuthorName = c.getString(c.getColumnIndexOrThrow(COL_REP_ORIGINAL_AUTHOR_NAME));
                    r.reposterPersonaId = c.getString(c.getColumnIndexOrThrow(COL_REP_REPOSTER_PERSONA_ID));
                    r.reposterName = c.getString(c.getColumnIndexOrThrow(COL_REP_REPOSTER_NAME));
                    r.quoteText = c.getString(c.getColumnIndexOrThrow(COL_REP_QUOTE_TEXT));
                    r.timestamp = c.getLong(c.getColumnIndexOrThrow(COL_REP_TIMESTAMP));
                    list.add(r);
                }
            } finally {
                c.close();
            }
        }
        return list;
    }

    public synchronized List<VeilBoardRepost> getAllReposts() {
        List<VeilBoardRepost> list = new ArrayList<>();
        SQLiteDatabase db = getEncryptedDb();
        Cursor c = db.query(TABLE_REPOSTS, null, null, null, null, null, COL_REP_TIMESTAMP + " DESC");
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    VeilBoardRepost r = new VeilBoardRepost();
                    r.repostId = c.getString(c.getColumnIndexOrThrow(COL_REP_ID));
                    r.originalPostId = c.getString(c.getColumnIndexOrThrow(COL_REP_ORIGINAL_POST_ID));
                    r.originalAuthorId = c.getString(c.getColumnIndexOrThrow(COL_REP_ORIGINAL_AUTHOR_ID));
                    r.originalAuthorName = c.getString(c.getColumnIndexOrThrow(COL_REP_ORIGINAL_AUTHOR_NAME));
                    r.reposterPersonaId = c.getString(c.getColumnIndexOrThrow(COL_REP_REPOSTER_PERSONA_ID));
                    r.reposterName = c.getString(c.getColumnIndexOrThrow(COL_REP_REPOSTER_NAME));
                    r.quoteText = c.getString(c.getColumnIndexOrThrow(COL_REP_QUOTE_TEXT));
                    r.timestamp = c.getLong(c.getColumnIndexOrThrow(COL_REP_TIMESTAMP));
                    list.add(r);
                }
            } finally {
                c.close();
            }
        }
        return list;
    }

    public static class ModerationTransparencyLog {
        public String logId;
        public String postId;
        public String reason;
        public int jurorCount;
        public String verdict; // "QUARANTINED", "DISMISSED", "REMOVED"
        public String contentHash; // SHA-256
        public long timestampMs;
        public String signature;
    }

    public synchronized boolean insertTransparencyLog(ModerationTransparencyLog log) {
        if (log == null) return false;
        SQLiteDatabase db = getEncryptedDb();
        ContentValues cv = new ContentValues();
        cv.put(COL_MTL_LOG_ID, log.logId);
        cv.put(COL_MTL_POST_ID, log.postId);
        cv.put(COL_MTL_REASON, log.reason);
        cv.put(COL_MTL_JUROR_COUNT, log.jurorCount);
        cv.put(COL_MTL_VERDICT, log.verdict);
        cv.put(COL_MTL_CONTENT_HASH, log.contentHash);
        cv.put(COL_MTL_TIMESTAMP_MS, log.timestampMs);
        cv.put(COL_MTL_SIGNATURE, log.signature);
        long res = db.insertWithOnConflict(TABLE_MODERATION_TRANSPARENCY_LOG, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        return res != -1;
    }

    public synchronized List<ModerationTransparencyLog> getTransparencyLogs() {
        List<ModerationTransparencyLog> list = new ArrayList<>();
        SQLiteDatabase db = getEncryptedDb();
        Cursor c = db.query(TABLE_MODERATION_TRANSPARENCY_LOG, null, null, null, null, null, COL_MTL_TIMESTAMP_MS + " DESC LIMIT 100");
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    ModerationTransparencyLog item = new ModerationTransparencyLog();
                    item.logId = c.getString(c.getColumnIndexOrThrow(COL_MTL_LOG_ID));
                    item.postId = c.getString(c.getColumnIndexOrThrow(COL_MTL_POST_ID));
                    item.reason = c.getString(c.getColumnIndexOrThrow(COL_MTL_REASON));
                    item.jurorCount = c.getInt(c.getColumnIndexOrThrow(COL_MTL_JUROR_COUNT));
                    item.verdict = c.getString(c.getColumnIndexOrThrow(COL_MTL_VERDICT));
                    item.contentHash = c.getString(c.getColumnIndexOrThrow(COL_MTL_CONTENT_HASH));
                    item.timestampMs = c.getLong(c.getColumnIndexOrThrow(COL_MTL_TIMESTAMP_MS));
                    item.signature = c.getString(c.getColumnIndexOrThrow(COL_MTL_SIGNATURE));
                    list.add(item);
                }
            } finally {
                c.close();
            }
        }
        return list;
    }
}
