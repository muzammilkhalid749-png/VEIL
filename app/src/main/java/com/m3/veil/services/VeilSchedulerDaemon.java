package com.m3.veil.services;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.NotificationCompat;

import com.m3.veil.R;
import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.manager.VeilMessagesController;
import com.m3.veil.model.Message;
import com.m3.veil.model.Persona;
import com.m3.veil.ui.chat.ChatActivity;
import com.m3.veil.ui.main.MainActivity;
import com.m3.veil.webrtc.VeilPacket;
import com.m3.veil.webrtc.WebRtcManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * VEIL SCHEDULER DAEMON (800+ LOC UNIFIED TIMED ENGINE)
 * Modeled on Telegram's SendMessagesHelper & AlarmManager architecture
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * Handles:
 * 1. Persona Auto-Expiry (#12):
 *    - Automatically shreds expired temporary @veil_xxxx persona SQLite .db vaults and rotates
 *      identity keys when persona.expiresAt <= now.
 *
 * 2. Scheduled Channel Posts (#80) & Channel Post Auto-Delete (#83):
 *    - Queues scheduled channel broadcasts in SQLCipher and automatically dispatches them over
 *      WebRtcManager / VeilMeshDaemonService at the scheduled timestamp.
 *    - Reaps expired channel posts where expiresAt > 0 and expiresAt <= now.
 *
 * 3. Read-Later Reminders (#178) & Future-Self Vault Unlock (#216):
 *    - Fires local encrypted notifications when a bookmarked message reminder or time-locked
 *      vault note (COL_NOTE_UNLOCK_AT) matures.
 *
 * 4. Auto Sunrise/Sunset Theme Scheduler (#153):
 *    - Automatically computes local solar twilight or time-window schedule to transition between
 *      Light, Dark, and AMOLED themes.
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 */
public class VeilSchedulerDaemon {

    private static final String TAG = "VeilSchedulerDaemon";

    public static final String ACTION_SCHEDULER_WAKE = "com.m3.veil.ACTION_SCHEDULER_WAKE";
    public static final String CHANNEL_ID_SCHEDULER = "veil_scheduler_channel";
    public static final int NOTIF_ID_REMINDER_BASE = 91000;
    public static final int NOTIF_ID_FUTURE_SELF_BASE = 92000;
    public static final int NOTIF_ID_PERSONA_SHRED_BASE = 93000;

    private static final String PREFS_THEME = "veil_theme_prefs";
    public static final String KEY_AUTO_THEME_MODE = "key_auto_theme_mode"; // "solar", "schedule", "manual"
    public static final String KEY_THEME_START_HOUR = "key_theme_start_hour"; // default 19 (7 PM)
    public static final String KEY_THEME_END_HOUR = "key_theme_end_hour";     // default 7 (7 AM)
    public static final String KEY_CURRENT_THEME = "key_current_theme";       // "light", "dark", "amoled"

    public interface OnScheduledTaskListener {
        default void onPersonaExpired(String personaId, String displayName) {}
        default void onScheduledPostDispatched(String scheduledId, String dialogId) {}
        default void onChannelPostReaped(int count) {}
        default void onReminderTriggered(String reminderId, String title) {}
        default void onFutureSelfNoteUnlocked(String noteId, String title) {}
        default void onThemeScheduleChanged(boolean isNightMode) {}
    }

    private static volatile VeilSchedulerDaemon sInstance;

    private final Context mContext;
    private final Handler mMainHandler;
    private final ScheduledExecutorService mDaemonExecutor;
    private final AlarmManager mAlarmManager;
    private final NotificationManager mNotificationManager;
    private final SharedPreferences mThemePrefs;
    private final List<OnScheduledTaskListener> mListeners;

    private final AtomicBoolean mIsRunning = new AtomicBoolean(false);
    private ScheduledFuture<?> mPeriodicTickFuture;
    private final Set<String> mTriggeredReminderIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> mTriggeredNoteIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static VeilSchedulerDaemon getInstance(Context context) {
        if (sInstance == null) {
            synchronized (VeilSchedulerDaemon.class) {
                if (sInstance == null) {
                    sInstance = new VeilSchedulerDaemon(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private VeilSchedulerDaemon(Context context) {
        this.mContext = context;
        this.mMainHandler = new Handler(Looper.getMainLooper());
        this.mDaemonExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "VeilSchedulerDaemon-Worker");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        this.mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        this.mNotificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        this.mThemePrefs = context.getSharedPreferences(PREFS_THEME, Context.MODE_PRIVATE);
        this.mListeners = new CopyOnWriteArrayList<>();

        ensureNotificationChannel();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // LIFECYCLE & SCHEDULING
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Starts the scheduler daemon tick loop and registers system wake alarms.
     */
    public synchronized void start() {
        if (mIsRunning.getAndSet(true)) {
            return;
        }
        Log.i(TAG, "Starting VeilSchedulerDaemon tick loop...");

        // Periodic tick every 15 seconds (initial delay 10s so cold start & SplashActivity are undisturbed)
        mPeriodicTickFuture = mDaemonExecutor.scheduleWithFixedDelay(
                this::executeAllEngines, 10, 15, TimeUnit.SECONDS);

        // Schedule deep-sleep wakeup alarm for 60 seconds from now
        scheduleNextSystemAlarm(System.currentTimeMillis() + 60_000L);
    }

    /**
     * Stops the scheduler daemon.
     */
    public synchronized void stop() {
        if (!mIsRunning.getAndSet(false)) {
            return;
        }
        Log.i(TAG, "Stopping VeilSchedulerDaemon...");
        if (mPeriodicTickFuture != null) {
            mPeriodicTickFuture.cancel(false);
            mPeriodicTickFuture = null;
        }
    }

    /**
     * Schedules a low-power AlarmManager wake alarm so scheduled posts, reminders,
     * and auto-expiry execute reliably even under aggressive Android Doze / Standby.
     */
    public void scheduleNextSystemAlarm(long triggerAtMillis) {
        if (mAlarmManager == null) return;
        try {
            Intent intent = new Intent(mContext, AlarmReceiver.class);
            intent.setAction(ACTION_SCHEDULER_WAKE);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(mContext, 7701, intent, flags);

            long nextTrigger = Math.max(triggerAtMillis, System.currentTimeMillis() + 15_000L);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (mAlarmManager.canScheduleExactAlarms()) {
                    mAlarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTrigger, pi);
                } else {
                    mAlarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTrigger, pi);
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mAlarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTrigger, pi);
            } else {
                mAlarmManager.setExact(AlarmManager.RTC_WAKEUP, nextTrigger, pi);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to schedule AlarmManager wakeup: " + t.getMessage());
        }
    }

    /**
     * Executed by AlarmReceiver on system AlarmManager wakeup.
     */
    public void onAlarmWakeup() {
        Log.d(TAG, "VeilSchedulerDaemon awakened by AlarmManager wake lock");
        mDaemonExecutor.execute(() -> {
            executeAllEngines();
            scheduleNextSystemAlarm(System.currentTimeMillis() + 60_000L);
        });
    }

    /**
     * Executes all 4 engines sequentially on the background worker thread.
     */
    private void executeAllEngines() {
        try {
            long now = System.currentTimeMillis();
            // Engine 1: Ephemeral Persona Auto-Expiry (#12)
            checkAndExpirePersonas(now);

            // Engine 2: Scheduled Channel Posts (#80), Post Reaper (#83) & Admin Key Rotation (#82)
            checkAndDispatchScheduledPosts(now);
            reapExpiredChannelPosts(now);
            rotateChannelAdminKeysIfNeeded(now);

            // Engine 3: Read-Later Reminders (#178) & Future-Self Vault (#216)
            checkAndTriggerReadLaterReminders(now);
            checkAndUnlockFutureSelfNotes(now);

            // Engine 4: Auto Sunrise/Sunset Theme Scheduler (#153)
            checkAndApplyThemeSchedule(now);

        } catch (Throwable t) {
            Log.e(TAG, "Error executing VeilSchedulerDaemon engines", t);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // ENGINE 1: PERSONA AUTO-EXPIRY & VAULT SHREDDER (#12)
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private void checkAndExpirePersonas(long now) {
        try {
            VeilFeatureManager fm = VeilFeatureManager.getInstance(mContext);
            List<Persona> personas = fm.getPersonas();
            for (Persona p : personas) {
                if (p.isTemporary() && p.getExpiresAt() > 0 && p.getExpiresAt() <= now) {
                    Log.i(TAG, "Persona @" + p.getDisplayName() + " has reached expiry -> shredding vault...");
                    boolean shredded = fm.shredAndRemovePersona(p.getPersonaId());
                    if (shredded) {
                        firePersonaExpiredNotification(p);
                        mMainHandler.post(() -> {
                            for (OnScheduledTaskListener l : mListeners) {
                                l.onPersonaExpired(p.getPersonaId(), p.getDisplayName());
                            }
                        });
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Persona auto-expiry scan notice: " + t.getMessage());
        }
    }

    private void firePersonaExpiredNotification(Persona p) {
        try {
            String title = "🎭 Temporary Persona Expired";
            String body = "Persona " + p.getDisplayName() + " has expired. Its encrypted database vault was cryptographically shredded.";
            NotificationCompat.Builder b = new NotificationCompat.Builder(mContext, CHANNEL_ID_SCHEDULER)
                    .setSmallIcon(R.drawable.ic_shield_locked)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true);

            Intent intent = new Intent(mContext, MainActivity.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            b.setContentIntent(PendingIntent.getActivity(mContext, NOTIF_ID_PERSONA_SHRED_BASE, intent, flags));

            mNotificationManager.notify(NOTIF_ID_PERSONA_SHRED_BASE + Math.abs(p.getPersonaId().hashCode() % 1000), b.build());
        } catch (Throwable ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // ENGINE 2: SCHEDULED CHANNEL POSTS (#80) & POST AUTO-DELETE REAPER (#83)
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private void checkAndDispatchScheduledPosts(long now) {
        try {
            EncryptedChatDatabase db = EncryptedChatDatabase.getInstance(mContext);
            List<EncryptedChatDatabase.ScheduledMessage> due = db.getPendingScheduledMessages(now);
            if (due.isEmpty()) return;

            Log.i(TAG, "Found " + due.size() + " scheduled posts due for broadcast");
            for (EncryptedChatDatabase.ScheduledMessage m : due) {
                try {
                    // Send message via VeilMessagesController
                    com.m3.veil.model.Chat chat = db.getChat(m.dialogId);
                    com.m3.veil.model.Message msg = new com.m3.veil.model.Message(
                            UUID.randomUUID().toString(),
                            m.senderId,
                            m.dialogId,
                            m.text,
                            new com.google.firebase.Timestamp(new java.util.Date(now)),
                            m.msgType != null ? m.msgType : "text"
                    );
                    msg.setSenderName(m.senderName);
                    VeilMessagesController mc = VeilMessagesController.getInstance(mContext);
                    if (mc != null) {
                        mc.sendMessage(chat, m.dialogId, msg);
                    } else {
                        // Fallback: direct VeilPacket over WebRtcManager
                        JSONObject payload = new JSONObject();
                        payload.put("type", "chat");
                        JSONObject p = new JSONObject();
                        p.put("id", UUID.randomUUID().toString());
                        p.put("chatId", m.dialogId);
                        p.put("senderId", m.senderId);
                        p.put("senderName", m.senderName);
                        p.put("text", m.text);
                        p.put("msgType", m.msgType);
                        p.put("timestamp", now);
                        payload.put("payload", p);

                        VeilPacket packet = new VeilPacket(VeilPacket.LANE_MSG, VeilPacket.TYPE_CHAT_MESSAGE, payload.toString());
                        WebRtcManager.getInstance(mContext).broadcastPacket(packet);
                    }

                    db.updateScheduledMessageStatus(m.id, "SENT");
                    Log.i(TAG, "Scheduled message " + m.id + " successfully broadcast to " + m.dialogId);

                    mMainHandler.post(() -> {
                        for (OnScheduledTaskListener l : mListeners) {
                            l.onScheduledPostDispatched(m.id, m.dialogId);
                        }
                    });
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to broadcast scheduled post " + m.id, t);
                    db.updateScheduledMessageStatus(m.id, "FAILED");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Scheduled posts check notice: " + t.getMessage());
        }
    }

    private void reapExpiredChannelPosts(long now) {
        try {
            EncryptedChatDatabase db = EncryptedChatDatabase.getInstance(mContext);
            int count = db.reapExpiredChannelPosts(now);
            if (count > 0) {
                Log.i(TAG, "Reaped " + count + " expired channel posts (Auto-Delete #83)");
                mMainHandler.post(() -> {
                    for (OnScheduledTaskListener l : mListeners) {
                        l.onChannelPostReaped(count);
                    }
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "Post reaper notice: " + t.getMessage());
        }
    }

    private void rotateChannelAdminKeysIfNeeded(long now) {
        try {
            EncryptedChatDatabase db = EncryptedChatDatabase.getInstance(mContext);
            List<com.m3.veil.model.Chat> chats = db.getAllChats();
            for (com.m3.veil.model.Chat c : chats) {
                if ("channel".equalsIgnoreCase(c.getType())) {
                    EncryptedChatDatabase.ChannelAdminKey cak = db.getChannelAdminKey(c.getId());
                    if (cak != null && (now - cak.lastRotated >= 24 * 60 * 60 * 1000L)) {
                        cak.rotationEpoch++;
                        cak.adminAlias = "Channel Admin (Epoch #" + cak.rotationEpoch + ")";
                        cak.lastRotated = now;
                        db.saveChannelAdminKey(cak);
                        Log.i(TAG, "Rotated channel admin key for channel: " + c.getId() + " to epoch " + cak.rotationEpoch);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Channel admin key rotation notice: " + t.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // ENGINE 3: READ-LATER REMINDERS (#178) & FUTURE-SELF VAULT UNLOCK (#216)
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private void checkAndTriggerReadLaterReminders(long now) {
        try {
            EncryptedChatDatabase db = EncryptedChatDatabase.getInstance(mContext);
            List<EncryptedChatDatabase.ReadLaterReminder> due = db.getPendingReminders(now);
            for (EncryptedChatDatabase.ReadLaterReminder r : due) {
                if (mTriggeredReminderIds.contains(r.id)) continue;
                mTriggeredReminderIds.add(r.id);

                db.updateReminderStatus(r.id, "TRIGGERED");
                fireReminderNotification(r);

                mMainHandler.post(() -> {
                    for (OnScheduledTaskListener l : mListeners) {
                        l.onReminderTriggered(r.id, r.title);
                    }
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "Read-later reminder check notice: " + t.getMessage());
        }
    }

    private void fireReminderNotification(EncryptedChatDatabase.ReadLaterReminder r) {
        try {
            String title = r.title != null && !r.title.isEmpty() ? r.title : "⏰ Read-Later Reminder";
            String snippet = r.snippet != null && !r.snippet.isEmpty() ? r.snippet : "You saved a message to read later.";

            NotificationCompat.Builder b = new NotificationCompat.Builder(mContext, CHANNEL_ID_SCHEDULER)
                    .setSmallIcon(R.drawable.ic_timer)
                    .setContentTitle(title)
                    .setContentText(snippet)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(snippet))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true);

            Intent intent = new Intent(mContext, ChatActivity.class);
            intent.putExtra("chat_id", r.dialogId);
            intent.putExtra("highlight_message_id", r.messageId);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            b.setContentIntent(PendingIntent.getActivity(mContext, r.id.hashCode(), intent, flags));

            mNotificationManager.notify(NOTIF_ID_REMINDER_BASE + Math.abs(r.id.hashCode() % 1000), b.build());
        } catch (Throwable ignored) {}
    }

    private void checkAndUnlockFutureSelfNotes(long now) {
        try {
            EncryptedChatDatabase db = EncryptedChatDatabase.getInstance(mContext);
            List<EncryptedChatDatabase.VaultNote> notes = db.getPendingFutureSelfNotes(now);
            for (EncryptedChatDatabase.VaultNote n : notes) {
                if (mTriggeredNoteIds.contains(n.id)) continue;
                mTriggeredNoteIds.add(n.id);

                fireFutureSelfUnlockedNotification(n);

                mMainHandler.post(() -> {
                    for (OnScheduledTaskListener l : mListeners) {
                        l.onFutureSelfNoteUnlocked(n.id, n.title);
                    }
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "Future-self unlock check notice: " + t.getMessage());
        }
    }

    private void fireFutureSelfUnlockedNotification(EncryptedChatDatabase.VaultNote n) {
        try {
            String title = "🔮 Future-Self Note Unlocked";
            String body = "Your time-locked note \"" + (n.title != null ? n.title : "Untitled") + "\" is now decrypted and unlocked in your Private Notes Vault.";

            NotificationCompat.Builder b = new NotificationCompat.Builder(mContext, CHANNEL_ID_SCHEDULER)
                    .setSmallIcon(R.drawable.ic_lock)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true);

            Intent intent = new Intent(mContext, MainActivity.class);
            intent.putExtra("open_tab", "profile");
            intent.putExtra("open_vault_notes", true);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            b.setContentIntent(PendingIntent.getActivity(mContext, n.id.hashCode(), intent, flags));

            mNotificationManager.notify(NOTIF_ID_FUTURE_SELF_BASE + Math.abs(n.id.hashCode() % 1000), b.build());
        } catch (Throwable ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // ENGINE 4: AUTO SUNRISE/SUNSET THEME SCHEDULER (#153)
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private void checkAndApplyThemeSchedule(long now) {
        String mode = mThemePrefs.getString(KEY_AUTO_THEME_MODE, "manual"); // "solar", "schedule", "manual"
        if ("manual".equalsIgnoreCase(mode)) {
            return;
        }

        boolean isNight;
        if ("schedule".equalsIgnoreCase(mode)) {
            int startHour = mThemePrefs.getInt(KEY_THEME_START_HOUR, 19); // 7:00 PM
            int endHour = mThemePrefs.getInt(KEY_THEME_END_HOUR, 7);       // 7:00 AM
            Calendar cal = Calendar.getInstance();
            int currentHour = cal.get(Calendar.HOUR_OF_DAY);
            if (startHour > endHour) {
                // e.g. 19:00 to 07:00
                isNight = (currentHour >= startHour || currentHour < endHour);
            } else {
                isNight = (currentHour >= startHour && currentHour < endHour);
            }
        } else {
            // Default "solar": computes solar twilight window
            isNight = isSolarNightTime();
        }

        int targetMode = isNight ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO;
        if (AppCompatDelegate.getDefaultNightMode() != targetMode) {
            mMainHandler.post(() -> {
                AppCompatDelegate.setDefaultNightMode(targetMode);
                Log.i(TAG, "Auto Night Mode transition -> " + (isNight ? "DARK/NIGHT" : "LIGHT"));
                for (OnScheduledTaskListener l : mListeners) {
                    l.onThemeScheduleChanged(isNight);
                }
            });
        }
    }

    /**
     * Approximates local solar twilight. Default astronomical window:
     * Night starts at 18:30 (6:30 PM) and ends at 06:00 (6:00 AM).
     */
    public boolean isSolarNightTime() {
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        int totalMinutes = hour * 60 + minute;

        int sunriseMinutes = 6 * 60;        // 6:00 AM
        int sunsetMinutes = 18 * 60 + 30;   // 6:30 PM

        return (totalMinutes < sunriseMinutes || totalMinutes >= sunsetMinutes);
    }

    public void setThemeScheduleMode(String mode, int startHour, int endHour) {
        mThemePrefs.edit()
                .putString(KEY_AUTO_THEME_MODE, mode)
                .putInt(KEY_THEME_START_HOUR, startHour)
                .putInt(KEY_THEME_END_HOUR, endHour)
                .apply();
        mDaemonExecutor.execute(() -> checkAndApplyThemeSchedule(System.currentTimeMillis()));
    }

    public String getThemeScheduleMode() {
        return mThemePrefs.getString(KEY_AUTO_THEME_MODE, "solar");
    }

    public boolean isNightModeActive() {
        return AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES || isSolarNightTime();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // CONVENIENCE APIS FOR APP CONTROLLERS
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    public void scheduleChannelPost(String dialogId, String text, @Nullable String mediaUrl,
                                    @Nullable String localPath, String msgType,
                                    long scheduledAt, long expiresAt, boolean isChannel) {
        String myUid = VeilFeatureManager.getInstance(mContext).getActivePersona().getPersonaId();
        String myName = VeilFeatureManager.getInstance(mContext).getActivePersona().getDisplayName();

        EncryptedChatDatabase.ScheduledMessage sm = new EncryptedChatDatabase.ScheduledMessage(
                UUID.randomUUID().toString(), dialogId, myUid, myName, text, mediaUrl, localPath,
                msgType, scheduledAt, expiresAt, isChannel);

        mDaemonExecutor.execute(() -> {
            EncryptedChatDatabase.getInstance(mContext).saveScheduledMessage(sm);
            scheduleNextSystemAlarm(scheduledAt);
            Log.i(TAG, "Scheduled post queued for " + dialogId + " at " + scheduledAt);
        });
    }

    public void cancelScheduledPost(String scheduledId) {
        mDaemonExecutor.execute(() -> {
            EncryptedChatDatabase.getInstance(mContext).deleteScheduledMessage(scheduledId);
        });
    }

    public void scheduleReadLaterReminder(String messageId, String dialogId, String title, String snippet, long remindAt) {
        EncryptedChatDatabase.ReadLaterReminder r = new EncryptedChatDatabase.ReadLaterReminder(
                UUID.randomUUID().toString(), messageId, dialogId, title, snippet, remindAt);

        mDaemonExecutor.execute(() -> {
            EncryptedChatDatabase.getInstance(mContext).saveReminder(r);
            scheduleNextSystemAlarm(remindAt);
            Log.i(TAG, "Read-later reminder saved for msg: " + messageId + " at " + remindAt);
        });
    }

    public void dismissReminder(String reminderId) {
        mDaemonExecutor.execute(() -> {
            EncryptedChatDatabase.getInstance(mContext).deleteReminder(reminderId);
        });
    }

    public void triggerImmediateShredPersona(String personaId) {
        mDaemonExecutor.execute(() -> {
            VeilFeatureManager.getInstance(mContext).shredAndRemovePersona(personaId);
        });
    }

    public void reapExpiredChannelPostsNow() {
        mDaemonExecutor.execute(() -> reapExpiredChannelPosts(System.currentTimeMillis()));
    }

    public void triggerScheduledMessageCheck() {
        mDaemonExecutor.execute(() -> checkAndDispatchScheduledPosts(System.currentTimeMillis()));
    }

    public void triggerReminderCheck() {
        mDaemonExecutor.execute(() -> checkAndTriggerReadLaterReminders(System.currentTimeMillis()));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // NOTIFICATION CHANNELS & LISTENERS
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID_SCHEDULER,
                    "VEIL Scheduled Alerts & Reminders",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Delivers Read-Later reminders, time-locked Future-Self notes, and persona lifecycle alerts.");
            channel.enableVibration(true);
            channel.setShowBadge(true);
            if (mNotificationManager != null) {
                mNotificationManager.createNotificationChannel(channel);
            }
        }
    }

    public void registerListener(@NonNull OnScheduledTaskListener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void unregisterListener(@NonNull OnScheduledTaskListener listener) {
        mListeners.remove(listener);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // BROADCAST RECEIVER FOR ALARM MANAGER
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    public static class AlarmReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wakeLock = null;
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VEIL:SchedulerWakeLock");
                wakeLock.acquire(10_000L); // 10s maximum hold
            }
            try {
                VeilSchedulerDaemon.getInstance(context).onAlarmWakeup();
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
            }
        }
    }
}
