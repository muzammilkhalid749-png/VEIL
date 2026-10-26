package com.m3.veil.calling;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import com.m3.veil.bridge.firestore.FirebaseFirestore;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Direct Client-to-Client FCM Push Dispatcher.
 *
 * <p>Ensures that callee devices are awakened immediately with full-screen VoIP UI
 * even when the callee's app is closed or killed in recents.
 *
 * <p>Supports:
 * 1. Native Google Service Account authentication (using assets/service-account.json if present).
 * 2. Caches OAuth2 Bearer tokens with auto-refresh before 1-hour expiry.
 * 3. Directly delivers high-priority Android FCM messages with custom TTL.
 */
public final class FcmCallPusher {

    private static final String TAG = "FcmCallPusher";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static volatile String cachedAccessToken = null;
    private static volatile long tokenExpiryMs = 0L;
    private static volatile ServiceAccountCredentials cachedCredentials = null;
    private static boolean assetsChecked = false;

    private static final class ServiceAccountCredentials {
        final String projectId;
        final String clientEmail;
        final PrivateKey privateKey;

        ServiceAccountCredentials(String projectId, String clientEmail, PrivateKey privateKey) {
            this.projectId = projectId;
            this.clientEmail = clientEmail;
            this.privateKey = privateKey;
        }
    }

    private FcmCallPusher() { }

    /**
     * Asynchronously dispatches a high-priority FCM push to the target user.
     *
     * @param context Application context
     * @param targetUid Callee's Firestore user UID
     * @param signalType "call_wake", "call_end", or "call_upgrade"
     * @param callId Current call identifier
     * @param senderId Caller's user UID
     * @param senderName Caller's display name
     * @param callType "audio" or "video"
     */
    public static void pushCallSignal(
            @NonNull Context context,
            @NonNull String targetUid,
            @NonNull String signalType,
            @NonNull String callId,
            @NonNull String senderId,
            @NonNull String senderName,
            @NonNull String callType) {

        final Context appContext = context.getApplicationContext();

        executor.execute(() -> {
            try {
                // 1. Fetch callee's FCM token from Firestore
                FirebaseFirestore.getInstance().collection("users").document(targetUid)
                        .get()
                        .addOnSuccessListener(documentSnapshot -> {
                            if (documentSnapshot == null || !documentSnapshot.exists()) return;
                            String fcmToken = documentSnapshot.getString("fcmToken");
                            if (fcmToken == null || fcmToken.trim().isEmpty()) {
                                Log.d(TAG, "No FCM token registered for user " + targetUid);
                                return;
                            }

                            executor.execute(() -> {
                                ensureCredentials(appContext, () -> {
                                    sendDirectFcm(appContext, fcmToken, signalType, callId, senderId, senderName, callType);
                                });
                            });
                        })
                        .addOnFailureListener(e -> Log.w(TAG, "Failed to resolve FCM token for user " + targetUid, e));
            } catch (Throwable t) {
                Log.w(TAG, "pushCallSignal execution failed", t);
            }
        });
    }

    /**
     * Dedicated Zero-Knowledge P2P Mesh Wake-Knock Dispatcher.
     * Wakes a sleeping peer within 500ms without disclosing sender identity or message content.
     * Writes to Firestore users/{targetUid}/mesh_signals/{signalId} (auto-purged by Cloud Function)
     * and simultaneously pushes a high-priority FCM data packet directly to the device.
     */
    public static void sendMeshWakeKnock(
            @NonNull Context context,
            @NonNull String targetUid,
            @NonNull String blindMailboxHash,
            @NonNull String ephemeralSignalId) {

        final Context appContext = context.getApplicationContext();

        executor.execute(() -> {
            try {
                // 1. Write ephemeral signal doc to Firestore (triggers Cloud Function fallback)
                java.util.Map<String, Object> signalData = new java.util.HashMap<>();
                signalData.put("type", "mesh_wake");
                signalData.put("mailbox", blindMailboxHash);
                signalData.put("timestamp", com.m3.veil.model.Timestamp.now());

                FirebaseFirestore.getInstance()
                        .collection("users")
                        .document(targetUid)
                        .collection("mesh_signals")
                        .document(ephemeralSignalId)
                        .set(signalData)
                        .addOnFailureListener(e -> Log.w(TAG, "Failed to write mesh_signals doc: " + e.getMessage()));

                // 2. Query target FCM token for direct client-to-client push
                FirebaseFirestore.getInstance().collection("users").document(targetUid)
                        .get()
                        .addOnSuccessListener(documentSnapshot -> {
                            if (documentSnapshot == null || !documentSnapshot.exists()) return;
                            String fcmToken = documentSnapshot.getString("fcmToken");
                            if (fcmToken == null || fcmToken.trim().isEmpty()) {
                                Log.d(TAG, "No FCM token for mesh wake user " + targetUid);
                                return;
                            }

                            executor.execute(() -> {
                                ensureCredentials(appContext, () -> {
                                    sendDirectMeshWakeFcm(appContext, fcmToken, blindMailboxHash, ephemeralSignalId);
                                });
                            });
                        });
            } catch (Throwable t) {
                Log.w(TAG, "sendMeshWakeKnock dispatch error", t);
            }
        });
    }

    private static void sendDirectMeshWakeFcm(
            Context context,
            String targetToken,
            String blindMailboxHash,
            String ephemeralSignalId) {
        ServiceAccountCredentials creds = cachedCredentials != null ? cachedCredentials : getCredentialsFromAssets(context);
        if (creds == null) {
            sendLegacyMeshWakeFcm(targetToken, blindMailboxHash, ephemeralSignalId);
            return;
        }

        try {
            String accessToken = getAccessToken(creds);
            if (accessToken == null) {
                sendLegacyMeshWakeFcm(targetToken, blindMailboxHash, ephemeralSignalId);
                return;
            }

            JSONObject message = new JSONObject();
            message.put("token", targetToken);

            JSONObject data = new JSONObject();
            data.put("type", "mesh_wake");
            data.put("mailbox", blindMailboxHash != null ? blindMailboxHash : "");
            data.put("signalId", ephemeralSignalId != null ? ephemeralSignalId : "");
            message.put("data", data);

            JSONObject androidConfig = new JSONObject();
            androidConfig.put("priority", "high");
            androidConfig.put("ttl", "60s");
            message.put("android", androidConfig);

            JSONObject root = new JSONObject();
            root.put("message", message);

            byte[] bodyBytes = root.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL("https://fcm.googleapis.com/v1/projects/" + creds.projectId + "/messages:send");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Content-Type", "application/json; UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
                os.flush();
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                Log.i(TAG, "Direct FCM mesh_wake push delivered successfully (<500ms)");
            } else {
                Log.w(TAG, "Direct FCM mesh_wake failed (" + code + "), falling back to legacy FCM");
                sendLegacyMeshWakeFcm(targetToken, blindMailboxHash, ephemeralSignalId);
            }
            conn.disconnect();

        } catch (Throwable t) {
            Log.w(TAG, "Direct FCM mesh_wake error, attempting legacy fallback", t);
            sendLegacyMeshWakeFcm(targetToken, blindMailboxHash, ephemeralSignalId);
        }
    }

    private static void sendLegacyMeshWakeFcm(
            String targetToken,
            String blindMailboxHash,
            String ephemeralSignalId) {
        try {
            JSONObject root = new JSONObject();
            root.put("to", targetToken);
            root.put("priority", "high");
            root.put("time_to_live", 60);

            JSONObject data = new JSONObject();
            data.put("type", "mesh_wake");
            data.put("mailbox", blindMailboxHash != null ? blindMailboxHash : "");
            data.put("signalId", ephemeralSignalId != null ? ephemeralSignalId : "");
            root.put("data", data);

            byte[] bodyBytes = root.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL("https://fcm.googleapis.com/fcm/send");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "key=" + com.m3.veil.BuildConfig.FCM_SERVER_KEY);
            conn.setRequestProperty("Content-Type", "application/json; UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
                os.flush();
            }

            int code = conn.getResponseCode();
            Log.i(TAG, "Legacy FCM mesh_wake push status: " + code);
            conn.disconnect();
        } catch (Throwable t) {
            Log.w(TAG, "Legacy FCM mesh_wake push failed: " + t.getMessage());
        }
    }

    private static void sendDirectFcm(
            Context context,
            String targetToken,
            String signalType,
            String callId,
            String senderId,
            String senderName,
            String callType) {

        ServiceAccountCredentials creds = cachedCredentials != null ? cachedCredentials : getCredentialsFromAssets(context);
        if (creds == null) {
            Log.d(TAG, "Direct FCM v1 skipped (no service-account.json); attempting legacy fallback push.");
            sendLegacyFcmPush(targetToken, signalType, callId, senderId, senderName, callType);
            return;
        }

        try {
            String accessToken = getAccessToken(creds);
            if (accessToken == null) {
                Log.w(TAG, "Failed to obtain OAuth2 token for FCM");
                return;
            }

            // Build FCM HTTP v1 JSON message
            JSONObject message = new JSONObject();
            message.put("token", targetToken);

            JSONObject data = new JSONObject();
            data.put("type", signalType);
            data.put("callId", callId != null ? callId : "");
            data.put("senderId", senderId != null ? senderId : "");
            data.put("senderName", senderName != null ? senderName : "Incoming Call");
            data.put("callType", callType != null ? callType : "audio");
            message.put("data", data);

            JSONObject androidConfig = new JSONObject();
            androidConfig.put("priority", "high");
            int ttlSec = "call_end".equals(signalType) ? 60 : 35;
            androidConfig.put("ttl", ttlSec + "s");
            message.put("android", androidConfig);

            JSONObject root = new JSONObject();
            root.put("message", message);

            byte[] bodyBytes = root.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL("https://fcm.googleapis.com/v1/projects/" + creds.projectId + "/messages:send");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Content-Type", "application/json; UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
                os.flush();
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                Log.i(TAG, "Direct FCM push (" + signalType + ") delivered to " + targetToken.substring(0, Math.min(10, targetToken.length())) + "...");
            } else {
                InputStream err = conn.getErrorStream();
                String errResponse = err != null ? readStream(err) : "code " + code;
                Log.w(TAG, "Direct FCM push failed (" + code + "): " + errResponse);
                if (code == 401) {
                    cachedAccessToken = null; // Invalidate cached token
                }
            }
            conn.disconnect();

        } catch (Throwable t) {
            Log.w(TAG, "Direct FCM dispatch error", t);
        }
    }

    private static void sendLegacyFcmPush(
            String targetToken,
            String signalType,
            String callId,
            String senderId,
            String senderName,
            String callType) {
        try {
            JSONObject root = new JSONObject();
            root.put("to", targetToken);
            root.put("priority", "high");
            int ttlSec = "call_end".equals(signalType) ? 60 : 35;
            root.put("time_to_live", ttlSec);

            JSONObject data = new JSONObject();
            data.put("type", signalType);
            data.put("callId", callId != null ? callId : "");
            data.put("senderId", senderId != null ? senderId : "");
            data.put("senderName", senderName != null ? senderName : "Incoming Call");
            data.put("callType", callType != null ? callType : "audio");
            root.put("data", data);

            byte[] bodyBytes = root.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL("https://fcm.googleapis.com/fcm/send");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "key=" + com.m3.veil.BuildConfig.FCM_SERVER_KEY);
            conn.setRequestProperty("Content-Type", "application/json; UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
                os.flush();
            }

            int code = conn.getResponseCode();
            Log.i(TAG, "Legacy FCM push status: " + code);
            conn.disconnect();
        } catch (Throwable t) {
            Log.w(TAG, "Legacy FCM push failed: " + t.getMessage());
        }
    }

    private static void ensureCredentials(Context context, Runnable onReady) {
        if (cachedCredentials != null) {
            onReady.run();
            return;
        }
        ServiceAccountCredentials creds = getCredentialsFromAssets(context);
        if (creds != null) {
            onReady.run();
            return;
        }
        // Fallback: Check Firestore system_config/fcm
        try {
            FirebaseFirestore.getInstance().collection("system_config").document("fcm")
                    .get()
                    .addOnSuccessListener(doc -> {
                        if (doc != null && doc.exists()) {
                            try {
                                String proj = doc.getString("projectId");
                                if (proj == null) proj = doc.getString("project_id");
                                String email = doc.getString("clientEmail");
                                if (email == null) email = doc.getString("client_email");
                                String pem = doc.getString("privateKey");
                                if (pem == null) pem = doc.getString("private_key");

                                if (proj != null && email != null && pem != null) {
                                    PrivateKey pk = parsePrivateKey(pem);
                                    cachedCredentials = new ServiceAccountCredentials(proj, email, pk);
                                    Log.i(TAG, "Loaded FCM service credentials from Firestore system_config/fcm");
                                }
                            } catch (Throwable t) {
                                Log.w(TAG, "Error parsing Firestore FCM credentials: " + t.getMessage());
                            }
                        }
                        onReady.run();
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "Failed to load system_config/fcm: " + e.getMessage());
                        onReady.run();
                    });
        } catch (Throwable t) {
            onReady.run();
        }
    }

    private static synchronized ServiceAccountCredentials getCredentialsFromAssets(Context context) {
        if (cachedCredentials != null) return cachedCredentials;
        if (assetsChecked) return null;
        assetsChecked = true;

        try {
            InputStream is = null;
            try {
                is = context.getAssets().open("service-account.json");
            } catch (Exception ignored) {
                try {
                    is = context.getAssets().open("serviceAccountKey.json");
                } catch (Exception ignored2) { }
            }

            if (is == null) return null;

            String jsonStr = readStream(is);
            JSONObject obj = new JSONObject(jsonStr);

            String projectId = obj.getString("project_id");
            String clientEmail = obj.getString("client_email");
            String privateKeyPem = obj.getString("private_key");

            PrivateKey privateKey = parsePrivateKey(privateKeyPem);
            cachedCredentials = new ServiceAccountCredentials(projectId, clientEmail, privateKey);
            Log.i(TAG, "Service account credentials loaded successfully from assets for " + projectId);
            return cachedCredentials;
        } catch (Throwable t) {
            Log.w(TAG, "Could not load service account from assets: " + t.getMessage());
            return null;
        }
    }

    private static PrivateKey parsePrivateKey(String pem) throws Exception {
        String cleanKey = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");

        byte[] decoded = Base64.decode(cleanKey, Base64.DEFAULT);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(spec);
    }

    private static synchronized String getAccessToken(ServiceAccountCredentials creds) {
        long now = System.currentTimeMillis();
        if (cachedAccessToken != null && now < tokenExpiryMs - 60_000L) {
            return cachedAccessToken;
        }

        try {
            long nowSec = now / 1000L;
            JSONObject header = new JSONObject();
            header.put("alg", "RS256");
            header.put("typ", "JWT");

            JSONObject claims = new JSONObject();
            claims.put("iss", creds.clientEmail);
            claims.put("scope", "https://www.googleapis.com/auth/firebase.messaging");
            claims.put("aud", "https://oauth2.googleapis.com/token");
            claims.put("exp", nowSec + 3600L);
            claims.put("iat", nowSec);

            String encHeader = Base64.encodeToString(header.toString().getBytes(StandardCharsets.UTF_8),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            String encClaims = Base64.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);

            String unsignedJwt = encHeader + "." + encClaims;

            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(creds.privateKey);
            sig.update(unsignedJwt.getBytes(StandardCharsets.UTF_8));
            byte[] signatureBytes = sig.sign();

            String encSignature = Base64.encodeToString(signatureBytes,
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);

            String assertion = unsignedJwt + "." + encSignature;

            // Exchange JWT with Google OAuth2 server
            URL tokenUrl = new URL("https://oauth2.googleapis.com/token");
            HttpURLConnection conn = (HttpURLConnection) tokenUrl.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);

            String body = "grant_type=" + "urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"
                    + "&assertion=" + assertion;

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                String resp = readStream(conn.getInputStream());
                JSONObject jsonResp = new JSONObject(resp);
                cachedAccessToken = jsonResp.getString("access_token");
                long expiresIn = jsonResp.optLong("expires_in", 3600L);
                tokenExpiryMs = System.currentTimeMillis() + (expiresIn * 1000L);
                return cachedAccessToken;
            } else {
                Log.w(TAG, "OAuth2 token exchange failed (" + code + "): " + readStream(conn.getErrorStream()));
            }
            conn.disconnect();
        } catch (Throwable t) {
            Log.w(TAG, "Failed to authenticate Google Service Account", t);
        }
        return null;
    }

    private static String readStream(InputStream is) {
        if (is == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }
}
