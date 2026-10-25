package com.m3.veil.ui.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.m3.veil.R;

public class NotificationSettingsActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "VeilNotifPrefs";
    public static final String KEY_PRIVATE_ENABLED = "notif_private_enabled";
    public static final String KEY_PRIVATE_PREVIEW = "notif_private_preview";
    public static final String KEY_PRIVATE_VIBRATE = "notif_private_vibrate";
    public static final String KEY_GROUP_ENABLED = "notif_group_enabled";
    public static final String KEY_CHANNEL_ENABLED = "notif_channel_enabled";
    public static final String KEY_MENTIONS_ONLY = "notif_mentions_only";
    public static final String KEY_CALL_RINGTONE = "notif_call_ringtone";
    public static final String KEY_CALL_VIBRATE = "notif_call_vibrate";
    public static final String KEY_INAPP_SOUNDS = "notif_inapp_sounds";
    public static final String KEY_INAPP_VIBRATE = "notif_inapp_vibrate";
    public static final String KEY_INAPP_PREVIEW = "notif_inapp_preview";

    private SwitchCompat switchPrivateNotifs;
    private SwitchCompat switchPrivatePreview;
    private SwitchCompat switchPrivateVibrate;
    private SwitchCompat switchGroupNotifs;
    private SwitchCompat switchChannelNotifs;
    private SwitchCompat switchMentionsOnly;
    private SwitchCompat switchCallRingtone;
    private SwitchCompat switchCallVibrate;
    private SwitchCompat switchInAppSounds;
    private SwitchCompat switchInAppVibrate;
    private SwitchCompat switchInAppPreview;
    private MaterialButton btnResetAll;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notification_settings);

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        bindViews();
        loadPreferences();
        setupListeners();
    }

    private void bindViews() {
        ImageView btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        switchPrivateNotifs = findViewById(R.id.switchPrivateNotifs);
        switchPrivatePreview = findViewById(R.id.switchPrivatePreview);
        switchPrivateVibrate = findViewById(R.id.switchPrivateVibrate);
        switchGroupNotifs = findViewById(R.id.switchGroupNotifs);
        switchChannelNotifs = findViewById(R.id.switchChannelNotifs);
        switchMentionsOnly = findViewById(R.id.switchMentionsOnly);
        switchCallRingtone = findViewById(R.id.switchCallRingtone);
        switchCallVibrate = findViewById(R.id.switchCallVibrate);
        switchInAppSounds = findViewById(R.id.switchInAppSounds);
        switchInAppVibrate = findViewById(R.id.switchInAppVibrate);
        switchInAppPreview = findViewById(R.id.switchInAppPreview);
        btnResetAll = findViewById(R.id.btnResetAllNotifications);
    }

    private void loadPreferences() {
        switchPrivateNotifs.setChecked(prefs.getBoolean(KEY_PRIVATE_ENABLED, true));
        switchPrivatePreview.setChecked(prefs.getBoolean(KEY_PRIVATE_PREVIEW, true));
        switchPrivateVibrate.setChecked(prefs.getBoolean(KEY_PRIVATE_VIBRATE, true));
        switchGroupNotifs.setChecked(prefs.getBoolean(KEY_GROUP_ENABLED, true));
        switchChannelNotifs.setChecked(prefs.getBoolean(KEY_CHANNEL_ENABLED, true));
        switchMentionsOnly.setChecked(prefs.getBoolean(KEY_MENTIONS_ONLY, false));
        switchCallRingtone.setChecked(prefs.getBoolean(KEY_CALL_RINGTONE, true));
        switchCallVibrate.setChecked(prefs.getBoolean(KEY_CALL_VIBRATE, true));
        switchInAppSounds.setChecked(prefs.getBoolean(KEY_INAPP_SOUNDS, true));
        switchInAppVibrate.setChecked(prefs.getBoolean(KEY_INAPP_VIBRATE, true));
        switchInAppPreview.setChecked(prefs.getBoolean(KEY_INAPP_PREVIEW, true));
    }

    private void setupListeners() {
        switchPrivateNotifs.setOnCheckedChangeListener((b, val) -> prefs.edit().putBoolean(KEY_PRIVATE_ENABLED, val).apply());
        switchPrivatePreview.setOnCheckedChangeListener((b, val) -> prefs.edit().putBoolean(KEY_PRIVATE_PREVIEW, val).apply());
        switchPrivateVibrate.setOnCheckedChangeListener((b, val) -> prefs.edit().putBoolean(KEY_PRIVATE_VIBRATE, val).apply());
        switchGroupNotifs.setOnCheckedChangeListener((b, val) -> prefs.edit().putBoolean(KEY_GROUP_ENABLED, val).apply());
        switchChannelNotifs.setOnCheckedChangeListener((b, val) -> prefs.edit().putBoolean(KEY_CHANNEL_ENABLED, val).apply());
        switchMentionsOnly.setOnCheckedChangeListener((b, val) -> prefs.edit().putBoolean(KEY_MENTIONS_ONLY, val).apply());
        switchCallRingtone.setOnCheckedChangeListener((b, val) -> prefs.edit().putBoolean(KEY_CALL_RINGTONE, val).apply());
        switchCallVibrate.setOnCheckedChangeListener((b, val) -> prefs.edit().putBoolean(KEY_CALL_VIBRATE, val).apply());
        switchInAppSounds.setOnCheckedChangeListener((b, val) -> prefs.edit().putBoolean(KEY_INAPP_SOUNDS, val).apply());
        switchInAppVibrate.setOnCheckedChangeListener((b, val) -> prefs.edit().putBoolean(KEY_INAPP_VIBRATE, val).apply());
        switchInAppPreview.setOnCheckedChangeListener((b, val) -> prefs.edit().putBoolean(KEY_INAPP_PREVIEW, val).apply());

        btnResetAll.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Reset Notification Settings?")
                    .setMessage("This will reset all push alert, tone, vibration, and banner preferences back to standard defaults.")
                    .setPositiveButton("Reset", (d, w) -> {
                        prefs.edit().clear().apply();
                        loadPreferences();
                        Toast.makeText(this, "Notification settings reset to default", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }
}
