package com.m3.veil.calling;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VoIP audio device: focus, routing and proximity handling.
 *
 * <p>Fixes the legacy behaviour of grabbing {@code AUDIOFOCUS_GAIN_TRANSIENT}
 * with a no-op listener. This holds a durable {@code AUDIOFOCUS_GAIN} (with
 * duck handling), manages communication devices explicitly on API 31+ and falls
 * back to SCO/speakerphone on older releases.
 */
public class CallAudioDevice {

    private static final String TAG = "CallAudioDevice";

    public interface ProximityListener {
        void onProximityChanged(boolean near);
    }

    public interface PcmAudioInterceptor {
        void onPcmAudioCaptured(byte[] buffer, int offset, int length, int sampleRate, int channels);
    }

    private static final VoiceMaskingDsp SHARED_DSP = new VoiceMaskingDsp();
    private final VoiceMaskingDsp voiceDsp = new VoiceMaskingDsp();
    private VoiceMaskingDsp.VoiceFilter activeVoiceFilter = VoiceMaskingDsp.VoiceFilter.NATURAL;
    private PcmAudioInterceptor pcmInterceptor;

    public static VoiceMaskingDsp getSharedDsp() {
        return SHARED_DSP;
    }

    public VoiceMaskingDsp.VoiceFilter getVoiceFilter() {
        return activeVoiceFilter;
    }

    public void setVoiceFilter(VoiceMaskingDsp.VoiceFilter filter) {
        this.activeVoiceFilter = filter != null ? filter : VoiceMaskingDsp.VoiceFilter.NATURAL;
        Log.i(TAG, "Voice filter set to: " + activeVoiceFilter.title);
    }

    public void setPcmInterceptor(PcmAudioInterceptor interceptor) {
        this.pcmInterceptor = interceptor;
    }

    public PcmAudioInterceptor getPcmInterceptor() {
        return pcmInterceptor;
    }

    /**
     * Intercepts and transforms a raw PCM audio buffer in-place before Opus transmission.
     */
    public void processPcmFrame(byte[] buffer, int offset, int length, int sampleRate, int channels) {
        if (activeVoiceFilter != VoiceMaskingDsp.VoiceFilter.NATURAL) {
            voiceDsp.processBytes(buffer, offset, length, sampleRate, channels, activeVoiceFilter);
        }
        if (pcmInterceptor != null) {
            try {
                pcmInterceptor.onPcmAudioCaptured(buffer, offset, length, sampleRate, channels);
            } catch (Throwable ignored) { }
        }
    }

    private volatile boolean lossyNetworkMode = false;

    public void setLossyNetworkMode(boolean lossy) {
        this.lossyNetworkMode = lossy;
    }

    public boolean isLossyNetworkMode() {
        return lossyNetworkMode;
    }

    private final Context context;
    private final AudioManager audioManager;
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();
    private CallAudioRoute route = CallAudioRoute.EARPIECE;
    private boolean muted;
    private boolean started;
    private boolean speakerOn;
    private int previousMode = AudioManager.MODE_NORMAL;

    private AudioFocusRequest focusRequest;
    private AudioManager.OnAudioFocusChangeListener focusListener;

    private SensorManager sensorManager;
    private Sensor proximitySensor;
    private ProximityListener proximityListener;
    private boolean proximityNear;

    public CallAudioDevice(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    // ---- lifecycle -----------------------------------------------------------

    public void start(boolean videoCall) {
        if (started || audioManager == null) return;
        started = true;
        previousMode = audioManager.getMode();
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        } catch (Throwable t) {
            Log.w(TAG, "setMode failed: " + t.getMessage());
        }
        requestFocus();
        setSpeakerOn(videoCall);
        registerProximity();
    }

    public void stop() {
        if (!started) return;
        started = false;
        releaseProximity();
        abandonFocus();
        if (audioManager != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice();
                }
            } catch (Throwable ignored) { }
            try {
                audioManager.stopBluetoothSco();
                audioManager.setBluetoothScoOn(false);
            } catch (Throwable ignored) { }
            try {
                audioManager.setSpeakerphoneOn(false);
            } catch (Throwable ignored) { }
            try {
                audioManager.setMode(AudioManager.MODE_NORMAL);
            } catch (Throwable ignored) { }
        }
    }

    private void requestFocus() {
        if (audioManager == null) return;
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            focusListener = change -> {
                switch (change) {
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                        Log.d(TAG, "focus transient loss");
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS:
                        Log.d(TAG, "focus lost permanently");
                        if (focusLostListener != null) focusLostListener.onFocusLost();
                        break;
                    default:
                        break;
                }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attrs)
                        .setAcceptsDelayedFocusGain(true)
                        .setWillPauseWhenDucked(false)
                        .setOnAudioFocusChangeListener(focusListener)
                        .build();
                audioManager.requestAudioFocus(focusRequest);
            } else {
                audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_VOICE_CALL,
                        AudioManager.AUDIOFOCUS_GAIN);
            }
        } catch (Throwable t) {
            Log.w(TAG, "requestFocus failed: " + t.getMessage());
        }
    }

    public interface FocusLostListener {
        void onFocusLost();
    }

    private FocusLostListener focusLostListener;

    public void setFocusLostListener(FocusLostListener listener) {
        this.focusLostListener = listener;
    }

    private void abandonFocus() {
        if (audioManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
                focusRequest = null;
            } else if (focusListener != null) {
                audioManager.abandonAudioFocus(focusListener);
            }
        } catch (Throwable ignored) { }
    }

    // ---- routing -------------------------------------------------------------

    public boolean isMuted() { return muted; }

    public void setMuted(boolean value) { this.muted = value; }

    public CallAudioRoute getRoute() { return route; }

    public boolean isSpeakerOn() {
        return route == CallAudioRoute.SPEAKER;
    }

    public void setSpeakerOn(boolean speaker) {
        speakerOn = speaker;
        if (speaker) {
            setRoute(CallAudioRoute.SPEAKER);
        } else if (route == CallAudioRoute.SPEAKER) {
            setRoute(CallAudioRoute.EARPIECE);
        }
    }

    public CallAudioRoute cycleRoute() {
        switch (route) {
            case EARPIECE:
                setRoute(CallAudioRoute.SPEAKER);
                break;
            case SPEAKER:
                setRoute(audioManager != null && audioManager.isBluetoothScoAvailableOffCall()
                        ? CallAudioRoute.BLUETOOTH : CallAudioRoute.EARPIECE);
                break;
            case BLUETOOTH:
                setRoute(isWiredHeadsetConnected() ? CallAudioRoute.WIRED_HEADSET : CallAudioRoute.EARPIECE);
                break;
            case WIRED_HEADSET:
                setRoute(CallAudioRoute.EARPIECE);
                break;
            default:
                setRoute(CallAudioRoute.EARPIECE);
                break;
        }
        return route;
    }

    public void setRoute(CallAudioRoute target) {
        route = target;
        if (audioManager == null) return;
        audioExecutor.execute(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    AudioDeviceInfo device = findCommunicationDevice(target);
                    if (device != null) {
                        audioManager.setCommunicationDevice(device);
                    }
                    audioManager.setSpeakerphoneOn(target == CallAudioRoute.SPEAKER);
                } else {
                    audioManager.setSpeakerphoneOn(target == CallAudioRoute.SPEAKER);
                    if (target == CallAudioRoute.BLUETOOTH) {
                        audioManager.startBluetoothSco();
                        audioManager.setBluetoothScoOn(true);
                    } else {
                        audioManager.stopBluetoothSco();
                        audioManager.setBluetoothScoOn(false);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "setRoute(" + target + ") failed: " + t.getMessage());
            }
        });
    }

    /**
     * Checks if a device is a Bluetooth audio device (SCO, BLE Headset/Speaker, or Hearing Aid),
     * matching the Telegram VoipAudioManager implementation.
     */
    public static boolean isBluetoothDevice(AudioDeviceInfo device) {
        if (device == null) return false;
        int type = device.getType();
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && type == AudioDeviceInfo.TYPE_HEARING_AID) return true;
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER);
    }

    private AudioDeviceInfo findCommunicationDevice(CallAudioRoute target) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || audioManager == null) return null;
        try {
            for (AudioDeviceInfo device : audioManager.getAvailableCommunicationDevices()) {
                int type = device.getType();
                switch (target) {
                    case SPEAKER:
                        if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return device;
                        break;
                    case BLUETOOTH:
                        if (isBluetoothDevice(device)) return device;
                        break;
                    case WIRED_HEADSET:
                        if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                                || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                                || type == AudioDeviceInfo.TYPE_USB_HEADSET) return device;
                        break;
                    case HEARING_AID:
                        if (type == AudioDeviceInfo.TYPE_HEARING_AID) return device;
                        break;
                    case EARPIECE:
                    default:
                        if (type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) return device;
                        break;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    public boolean isWiredHeadsetConnected() {
        if (audioManager == null) return false;
        try {
            for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int type = device.getType();
                if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || type == AudioDeviceInfo.TYPE_USB_HEADSET) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    public boolean isBluetoothAvailable() {
        if (audioManager == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                for (AudioDeviceInfo device : audioManager.getAvailableCommunicationDevices()) {
                    if (isBluetoothDevice(device)) return true;
                }
            }
            return audioManager.isBluetoothScoAvailableOffCall();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---- proximity -----------------------------------------------------------

    public void setProximityListener(ProximityListener listener) {
        this.proximityListener = listener;
    }

    public boolean isProximityNear() {
        return proximityNear;
    }

    @SuppressWarnings("deprecation")
    private void registerProximity() {
        try {
            sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager == null) return;
            proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (proximitySensor == null) return;
            sensorManager.registerListener(proximityEvents, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
        } catch (Throwable ignored) { }
    }

    private void releaseProximity() {
        try {
            if (sensorManager != null && proximityEvents != null) {
                sensorManager.unregisterListener(proximityEvents);
            }
        } catch (Throwable ignored) { }
        sensorManager = null;
        proximitySensor = null;
    }

    private final SensorEventListener proximityEvents = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            boolean near = event.values != null && event.values.length > 0
                    && event.values[0] < (proximitySensor != null ? proximitySensor.getMaximumRange() : 5f);
            if (near != proximityNear) {
                proximityNear = near;
                if (proximityListener != null) proximityListener.onProximityChanged(near);
            }
        }

        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };
}
