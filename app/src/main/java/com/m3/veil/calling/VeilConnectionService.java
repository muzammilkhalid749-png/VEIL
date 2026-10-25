package com.m3.veil.calling;

import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telecom.CallAudioState;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.m3.veil.R;

/**
 * Android Telecom ConnectionService integration ported from Telegram
 * (org.telegram.messenger.voip.TelegramConnectionService).
 *
 * <p>Enables native system-level VoIP handling:
 * <ul>
 *   <li>System lock-screen call controls and heads-up integration</li>
 *   <li>Car head-unit / Android Auto and Bluetooth headset button handling</li>
 *   <li>Graceful audio interruption/hold when cellular GSM calls arrive</li>
 * </ul>
 */
@TargetApi(Build.VERSION_CODES.O)
public class VeilConnectionService extends ConnectionService {

    private static final String TAG = "VeilConnectionService";
    private static final String ACCOUNT_ID = "veil_voip_account";

    public static PhoneAccountHandle getPhoneAccountHandle(Context context) {
        return new PhoneAccountHandle(
                new ComponentName(context, VeilConnectionService.class),
                ACCOUNT_ID
        );
    }

    public static void registerAccount(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            TelecomManager tm = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            if (tm == null) return;
            PhoneAccountHandle handle = getPhoneAccountHandle(context);
            PhoneAccount account = PhoneAccount.builder(handle, "VEIL Calls")
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .setIcon(android.graphics.drawable.Icon.createWithResource(context, R.mipmap.ic_launcher))
                    .setHighlightColor(context.getColor(R.color.colorPrimary))
                    .addSupportedUriScheme(PhoneAccount.SCHEME_SIP)
                    .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
                    .build();
            tm.registerPhoneAccount(account);
            Log.i(TAG, "PhoneAccount registered successfully");
        } catch (Throwable t) {
            Log.w(TAG, "registerAccount failed: " + t.getMessage());
        }
    }

    @Override
    public Connection onCreateIncomingConnection(PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        Log.d(TAG, "onCreateIncomingConnection");
        VeilCallConnection connection = new VeilCallConnection(this, request);
        connection.setRinging();
        return connection;
    }

    @Override
    public Connection onCreateOutgoingConnection(PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        Log.d(TAG, "onCreateOutgoingConnection");
        VeilCallConnection connection = new VeilCallConnection(this, request);
        connection.setDialing();
        return connection;
    }

    @Override
    public void onCreateIncomingConnectionFailed(PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        Log.w(TAG, "onCreateIncomingConnectionFailed");
    }

    @Override
    public void onCreateOutgoingConnectionFailed(PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        Log.w(TAG, "onCreateOutgoingConnectionFailed");
    }

    /**
     * Managed system connection that bridges Android Telecom callbacks
     * to VEIL's CallEngine.
     */
    public static class VeilCallConnection extends Connection {
        private final Context context;

        public VeilCallConnection(Context context, ConnectionRequest request) {
            this.context = context;
            setConnectionProperties(PROPERTY_SELF_MANAGED);
            setAudioModeIsVoip(true);
            if (request != null && request.getAddress() != null) {
                setAddress(request.getAddress(), TelecomManager.PRESENTATION_ALLOWED);
            }
        }

        @Override
        public void onAnswer() {
            Log.d(TAG, "Connection onAnswer triggered (e.g. from headset or system UI)");
            setActive();
            CallEngine engine = CallEngine.getInstance(context);
            if (engine != null) {
                engine.accept();
            }
        }

        @Override
        public void onReject() {
            Log.d(TAG, "Connection onReject triggered");
            setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));
            destroy();
            CallEngine engine = CallEngine.getInstance(context);
            if (engine != null) {
                engine.decline(CallEndReason.DECLINED);
            }
        }

        @Override
        public void onDisconnect() {
            Log.d(TAG, "Connection onDisconnect triggered");
            setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
            destroy();
            CallEngine engine = CallEngine.getInstance(context);
            if (engine != null) {
                engine.hangup(CallEndReason.LOCAL_HANGUP);
            }
        }

        @Override
        public void onCallAudioStateChanged(CallAudioState state) {
            Log.d(TAG, "Connection onCallAudioStateChanged: " + state);
        }

        @Override
        public void onSilence() {
            Log.d(TAG, "Connection onSilence triggered");
        }
    }
}
