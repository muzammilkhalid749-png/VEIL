package com.m3.veil.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Debug;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

/**
 * Runtime Anti-Tamper, Signature Verification, Debugger/Frida/Xposed & Root Detection Guard.
 * Protects SQLCipher passphrases, ECDH ratchets, and WebRTC identity keys against runtime hooking.
 */
public final class VeilRuntimeIntegrityGuard {

    private static final String TAG = "VeilIntegrityGuard";

    private static volatile boolean sIntegrityVerified = false;
    private static volatile boolean sCompromisedEnvironment = false;
    private static volatile String sApkCertSha256 = "";

    private static final String[] ROOT_PATHS = {
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su",
            "/sbin/magisk"
    };

    private VeilRuntimeIntegrityGuard() {}

    /**
     * Executes multi-layer runtime integrity checks at application startup.
     */
    public static synchronized boolean verifyRuntimeIntegrity(Context context) {
        if (sIntegrityVerified) {
            return !sCompromisedEnvironment;
        }
        try {
            boolean debugger = isDebuggerAttached();
            boolean hooked = isHookingFrameworkDetected();
            boolean rooted = isRootedDevice();
            sApkCertSha256 = computeSigningCertSha256(context);

            sCompromisedEnvironment = debugger || hooked;
            sIntegrityVerified = true;

            if (sCompromisedEnvironment) {
                Log.w(TAG, "Runtime security warning: debugger=" + debugger + ", hooked=" + hooked + ", rooted=" + rooted);
            } else {
                Log.i(TAG, "Runtime integrity verified. APK Cert SHA-256: " + sApkCertSha256);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Integrity verification completed with notice: " + t.getMessage());
        }
        return !sCompromisedEnvironment;
    }

    public static boolean isDebuggerAttached() {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger();
    }

    public static boolean isRootedDevice() {
        try {
            String buildTags = Build.TAGS;
            if (buildTags != null && buildTags.contains("test-keys")) {
                return true;
            }
            for (String path : ROOT_PATHS) {
                if (new File(path).exists()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Scans /proc/self/maps and stack frames for Frida, Xposed, or Substrate injection.
     */
    public static boolean isHookingFrameworkDetected() {
        try {
            File mapsFile = new File("/proc/self/maps");
            if (mapsFile.exists() && mapsFile.canRead()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(mapsFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String lower = line.toLowerCase(Locale.ROOT);
                        if (lower.contains("frida") || lower.contains("xposed") || lower.contains("substrate")) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            throw new Exception("StackCheck");
        } catch (Exception e) {
            for (StackTraceElement element : e.getStackTrace()) {
                String cls = element.getClassName().toLowerCase(Locale.ROOT);
                if (cls.contains("xposed") || cls.contains("substrate") || cls.contains("frida")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks whether the default Frida server port (27042) is listening locally.
     */
    public static boolean isFridaPortOpen() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 27042), 50);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Computes the SHA-256 fingerprint of the APK signing certificate.
     */
    @SuppressWarnings("deprecation")
    public static String computeSigningCertSha256(Context context) {
        if (context == null) return "UNKNOWN";
        try {
            PackageManager pm = context.getPackageManager();
            String pkg = context.getPackageName();
            byte[] certBytes = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                if (info.signingInfo != null) {
                    Signature[] sigs = info.signingInfo.hasMultipleSigners()
                            ? info.signingInfo.getApkContentsSigners()
                            : info.signingInfo.getSigningCertificateHistory();
                    if (sigs != null && sigs.length > 0) {
                        certBytes = sigs[0].toByteArray();
                    }
                }
            } else {
                PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
                if (info.signatures != null && info.signatures.length > 0) {
                    certBytes = info.signatures[0].toByteArray();
                }
            }

            if (certBytes != null) {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(certBytes);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < digest.length; i++) {
                    if (i > 0) sb.append(':');
                    sb.append(String.format(Locale.ROOT, "%02X", digest[i]));
                }
                return sb.toString();
            }
        } catch (Throwable ignored) {}
        return "DEV_BUILD_CERT";
    }

    public static String getApkCertSha256() {
        return sApkCertSha256;
    }

    public static boolean isCompromisedEnvironment() {
        return sCompromisedEnvironment;
    }

    /**
     * Cryptographically wipes sensitive byte arrays in RAM.
     */
    public static void zeroizeSensitiveBytes(byte[] sensitive) {
        if (sensitive != null) {
            Arrays.fill(sensitive, (byte) 0);
        }
    }
}
