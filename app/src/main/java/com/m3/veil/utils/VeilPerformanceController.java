package com.m3.veil.utils;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import java.io.RandomAccessFile;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * VEIL Hardware Profiler & Auto-Adaptive Performance Controller.
 *
 * <p>Modeled directly on Telegram's {@code org.telegram.messenger.SharedConfig}
 * ({@code measureDevicePerformanceClass}), {@code LiteMode}, and modern Android
 * thermal/power management APIs.
 *
 * <p>Dynamically grades every Android handset into three concrete tiers:
 * <ul>
 *   <li>{@link #PERFORMANCE_CLASS_LOW} (0): 2GB–3GB RAM devices, <=4 cores <=1.6GHz, or
 *       {@code isLowRamDevice()}. Enforces minimal memory footprints (RGB_565, 480p@24fps,
 *       max 3 WebRTC lanes, no expensive multi-pass blur shaders).</li>
 *   <li>{@link #PERFORMANCE_CLASS_AVERAGE} (1): 4GB–6GB RAM devices, 8 cores <=2.0GHz.
 *       Balanced profile (720p@30fps, 6 WebRTC lanes, 2 status preloads).</li>
 *   <li>{@link #PERFORMANCE_CLASS_HIGH} (2): 8GB+ RAM flagships with modern SoCs. Full
 *       fidelity (1080p@60fps, 10 WebRTC swarm lanes, 120Hz display pacing, 5 status preloads).</li>
 * </ul>
 *
 * <p>Includes real-time thermal throttling compensation (API 29+) and power-saver
 * adaptations to prevent device overheating and thermal shutdowns during prolonged calls.
 */
public class VeilPerformanceController {

    private static final String TAG = "VeilPerfController";
    private static final String PREF_NAME = "veil_perf_config";
    private static final String KEY_CACHED_CLASS = "cached_perf_class";

    public static final int PERFORMANCE_CLASS_LOW = 0;
    public static final int PERFORMANCE_CLASS_AVERAGE = 1;
    public static final int PERFORMANCE_CLASS_HIGH = 2;

    private static volatile VeilPerformanceController instance;

    public interface PerformanceListener {
        default void onPerformanceClassChanged(int oldClass, int newClass) { }
        default void onThermalStatusChanged(int thermalStatus) { }
        default void onPowerSaveModeChanged(boolean isPowerSaveMode) { }
        default void onLowMemoryCondition(boolean isLowMemory) { }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final List<PerformanceListener> listeners = new CopyOnWriteArrayList<>();

    private int basePerformanceClass = -1;
    private volatile int effectivePerformanceClass = -1;

    // Hardware specifications
    private int cpuCoreCount = 1;
    private int maxCpuFrequencyMhz = -1;
    private long totalRamBytes = -1;
    private int memoryClassMb = 128;
    private boolean isLowRamDevice = false;
    private float maxScreenRefreshRate = 60.0f;

    // Real-time dynamic states
    private volatile int currentThermalStatus = 0; // 0 = THERMAL_STATUS_NONE
    private volatile boolean isPowerSaveMode = false;
    private volatile int batteryPercentage = 100;
    private volatile boolean isCharging = false;

    private PowerManager.OnThermalStatusChangedListener thermalListener;
    private BroadcastReceiver powerReceiver;

    public static VeilPerformanceController getInstance() {
        if (instance == null) {
            throw new IllegalStateException("VeilPerformanceController must be initialized first with init(Context)");
        }
        return instance;
    }

    public static synchronized void init(Context context) {
        if (instance == null) {
            instance = new VeilPerformanceController(context.getApplicationContext());
        }
    }

    private VeilPerformanceController(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        // Instant startup restore from cached assessment
        int cached = prefs.getInt(KEY_CACHED_CLASS, -1);
        if (cached >= 0) {
            this.effectivePerformanceClass = cached;
            this.basePerformanceClass = cached;
        }

        // Run full hardware measurement
        measureHardwareProfile();

        // Listen for thermal, battery, and power changes
        registerDynamicMonitors();

        Log.i(TAG, "VeilPerformanceController active: class=" + getPerformanceClassName(effectivePerformanceClass)
                + " (CPU=" + cpuCoreCount + " cores @" + maxCpuFrequencyMhz + "MHz, RAM="
                + (totalRamBytes / (1024 * 1024)) + "MB, RefreshRate=" + maxScreenRefreshRate + "Hz)");
    }

    public void addListener(PerformanceListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(PerformanceListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    // ---- Hardware Measurement ------------------------------------------------

    private void measureHardwareProfile() {
        // 1. CPU cores
        cpuCoreCount = Math.max(1, Runtime.getRuntime().availableProcessors());

        // 2. CPU max frequency by probing sysfs
        int totalCpuFreq = 0;
        int freqResolved = 0;
        for (int i = 0; i < cpuCoreCount; i++) {
            try {
                RandomAccessFile reader = new RandomAccessFile(
                        String.format(Locale.ENGLISH, "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i), "r");
                String line = reader.readLine();
                if (line != null) {
                    int khz = Integer.parseInt(line.trim());
                    totalCpuFreq += (khz / 1000);
                    freqResolved++;
                }
                reader.close();
            } catch (Throwable ignored) { }
        }
        maxCpuFrequencyMhz = freqResolved > 0 ? (int) Math.ceil((float) totalCpuFreq / freqResolved) : -1;

        // 3. RAM measurement
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                memoryClassMb = am.getMemoryClass();
                isLowRamDevice = am.isLowRamDevice();
                ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(memInfo);
                totalRamBytes = memInfo.totalMem;
            }
        } catch (Throwable t) {
            Log.w(TAG, "RAM measurement error: " + t.getMessage());
        }

        // 4. Display refresh rate
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                Display display = wm.getDefaultDisplay();
                if (display != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Display.Mode mode = display.getMode();
                        if (mode != null) {
                            maxScreenRefreshRate = mode.getRefreshRate();
                        }
                    } else {
                        maxScreenRefreshRate = display.getRefreshRate();
                    }
                }
            }
        } catch (Throwable ignored) { }

        // 5. Evaluate Base Performance Class
        this.basePerformanceClass = calculateBaseTier();
        prefs.edit().putInt(KEY_CACHED_CLASS, basePerformanceClass).apply();

        recalculateEffectiveClass();
    }

    private int calculateBaseTier() {
        int androidVersion = Build.VERSION.SDK_INT;
        long ramGb = totalRamBytes > 0 ? (totalRamBytes / (1024L * 1024L * 1024L)) : -1;

        // Low Tier criteria (2GB-3GB RAM or low-frequency quad-core)
        if (isLowRamDevice
                || (ramGb != -1 && ramGb < 3L)
                || memoryClassMb <= 128
                || cpuCoreCount <= 4 && maxCpuFrequencyMhz != -1 && maxCpuFrequencyMhz <= 1650
                || cpuCoreCount <= 2
                || androidVersion <= Build.VERSION_CODES.N_MR1) {
            return PERFORMANCE_CLASS_LOW;
        }

        // Average Tier criteria (4GB-6GB RAM or mid-range octa-core)
        if ((ramGb != -1 && ramGb <= 6L)
                || memoryClassMb <= 256
                || cpuCoreCount < 8
                || (maxCpuFrequencyMhz != -1 && maxCpuFrequencyMhz <= 2200)) {
            return PERFORMANCE_CLASS_AVERAGE;
        }

        // High Tier criteria (8GB+ RAM, high-performance octa-core)
        return PERFORMANCE_CLASS_HIGH;
    }

    private void recalculateEffectiveClass() {
        int target = basePerformanceClass;

        // Thermal Throttling Compensation:
        // On SEVERE (3) or CRITICAL (4), throttle to LOW to save the hardware
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                target = PERFORMANCE_CLASS_LOW;
            } else if (currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
                target = Math.min(target, PERFORMANCE_CLASS_AVERAGE);
            }
        }

        // Battery Power Save Mode Compensation:
        // If phone is in system Battery Saver mode, cap at AVERAGE
        if (isPowerSaveMode && target == PERFORMANCE_CLASS_HIGH) {
            target = PERFORMANCE_CLASS_AVERAGE;
        }

        if (target != effectivePerformanceClass) {
            int old = effectivePerformanceClass;
            effectivePerformanceClass = target;
            Log.i(TAG, "Effective performance class transitioned: " + getPerformanceClassName(old)
                    + " -> " + getPerformanceClassName(target) + " (thermal=" + currentThermalStatus
                    + ", powerSave=" + isPowerSaveMode + ")");
            for (PerformanceListener l : listeners) {
                l.onPerformanceClassChanged(old, target);
            }
        }
    }

    // ---- Dynamic Monitors (Thermal & Power) -----------------------------------

    private void registerDynamicMonitors() {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            isPowerSaveMode = pm.isPowerSaveMode();

            // Android 10+ Thermal Throttling Listener
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    thermalListener = status -> {
                        currentThermalStatus = status;
                        recalculateEffectiveClass();
                        for (PerformanceListener l : listeners) {
                            l.onThermalStatusChanged(status);
                        }
                    };
                    pm.addThermalStatusListener(context.getMainExecutor(), thermalListener);
                } catch (Throwable t) {
                    Log.w(TAG, "addThermalStatusListener failed: " + t.getMessage());
                }
            }
        }

        // Battery and Power Save broadcasts
        powerReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                String action = intent.getAction();
                if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(action)) {
                    PowerManager power = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                    if (power != null) {
                        isPowerSaveMode = power.isPowerSaveMode();
                        recalculateEffectiveClass();
                        for (PerformanceListener l : listeners) {
                            l.onPowerSaveModeChanged(isPowerSaveMode);
                        }
                    }
                } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                    int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    if (level >= 0 && scale > 0) {
                        batteryPercentage = (int) ((level / (float) scale) * 100);
                    }
                    int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                            || status == BatteryManager.BATTERY_STATUS_FULL;
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        context.registerReceiver(powerReceiver, filter);
    }

    // ---- Adaptive Budgets & Tuners -------------------------------------------

    public int getDevicePerformanceClass() {
        return effectivePerformanceClass;
    }

    public int getBasePerformanceClass() {
        return basePerformanceClass;
    }

    public boolean isLowEndDevice() {
        return effectivePerformanceClass == PERFORMANCE_CLASS_LOW;
    }

    public boolean isHighEndDevice() {
        return effectivePerformanceClass == PERFORMANCE_CLASS_HIGH;
    }

    /** Maximum parallel WebRTC DataChannel lanes / mesh peer connections. */
    public int getMaxWebRtcConnections() {
        switch (effectivePerformanceClass) {
            case PERFORMANCE_CLASS_LOW:
                return 3;
            case PERFORMANCE_CLASS_AVERAGE:
                return 6;
            case PERFORMANCE_CLASS_HIGH:
            default:
                return 10;
        }
    }

    /** Maximum concurrent file transfer chunk reads / writes. */
    public int getMaxParallelFileTransferChunks() {
        switch (effectivePerformanceClass) {
            case PERFORMANCE_CLASS_LOW:
                return 2;
            case PERFORMANCE_CLASS_AVERAGE:
                return 4;
            case PERFORMANCE_CLASS_HIGH:
            default:
                return 8;
        }
    }

    /** WebRTC Video Capture Width (480p / 720p / 1080p). */
    public int getVideoWidth() {
        switch (effectivePerformanceClass) {
            case PERFORMANCE_CLASS_LOW:
                return 640;
            case PERFORMANCE_CLASS_AVERAGE:
                return 1280;
            case PERFORMANCE_CLASS_HIGH:
            default:
                return 1920;
        }
    }

    /** WebRTC Video Capture Height (480p / 720p / 1080p). */
    public int getVideoHeight() {
        switch (effectivePerformanceClass) {
            case PERFORMANCE_CLASS_LOW:
                return 480;
            case PERFORMANCE_CLASS_AVERAGE:
                return 720;
            case PERFORMANCE_CLASS_HIGH:
            default:
                return 1080;
        }
    }

    /** WebRTC Video Target FPS (24fps / 30fps / 60fps). */
    public int getVideoFps() {
        switch (effectivePerformanceClass) {
            case PERFORMANCE_CLASS_LOW:
                return 24;
            case PERFORMANCE_CLASS_AVERAGE:
                return 30;
            case PERFORMANCE_CLASS_HIGH:
            default:
                return 60;
        }
    }

    /**
     * Preferred bitmap configuration for caching and thumbnail rendering.
     * LOW-end devices use RGB_565 (2 bytes per pixel) saving 50% RAM.
     * AVERAGE and HIGH devices use ARGB_8888 (4 bytes per pixel) for full alpha fidelity.
     */
    public Bitmap.Config getPreferredBitmapConfig() {
        return isLowEndDevice() ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
    }

    /**
     * True if expensive multi-pass OpenGL or software Gaussian blur shaders can run.
     * Low-end devices bypass blur shaders and use sleek translucent flat scrims.
     */
    public boolean isBlurShaderEnabled() {
        return effectivePerformanceClass >= PERFORMANCE_CLASS_AVERAGE;
    }

    /** Look-ahead Status / Story preload count (1 / 2 / 5). */
    public int getStatusPreloadCount() {
        switch (effectivePerformanceClass) {
            case PERFORMANCE_CLASS_LOW:
                return 1;
            case PERFORMANCE_CLASS_AVERAGE:
                return 2;
            case PERFORMANCE_CLASS_HIGH:
            default:
                return 5;
        }
    }

    /** High refresh rate (90Hz / 120Hz) flag for flagship UI animations. */
    public boolean isHighRefreshRateDisplay() {
        return maxScreenRefreshRate >= 85.0f;
    }

    public float getMaxScreenRefreshRate() {
        return maxScreenRefreshRate;
    }

    public int getCpuCoreCount() {
        return cpuCoreCount;
    }

    public int getMaxCpuFrequencyMhz() {
        return maxCpuFrequencyMhz;
    }

    public long getTotalRamBytes() {
        return totalRamBytes;
    }

    public int getBatteryPercentage() {
        return batteryPercentage;
    }

    public boolean isPowerSaveMode() {
        return isPowerSaveMode;
    }

    public int getCurrentThermalStatus() {
        return currentThermalStatus;
    }

    public static String getPerformanceClassName(int perfClass) {
        switch (perfClass) {
            case PERFORMANCE_CLASS_LOW:
                return "LOW";
            case PERFORMANCE_CLASS_AVERAGE:
                return "AVERAGE";
            case PERFORMANCE_CLASS_HIGH:
                return "HIGH";
            default:
                return "UNKNOWN";
        }
    }
}
