package com.m3.veil.ui.main.fragments;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.m3.veil.R;
import com.m3.veil.manager.AuthManager;
import com.m3.veil.model.User;
import com.m3.veil.utils.UserPreferences;
import com.m3.veil.webrtc.WebRtcManager;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class LinkedDevicesBottomSheet extends BottomSheetDialogFragment {
    private static final String TAG = "LinkedDevicesSheet";
    private static final String PREF_LINKED_WEB = "veil_linked_desktop_device";

    private TextView tvDeviceTitle;
    private TextView tvDeviceSubtitle;
    private MaterialButton btnUnlinkDevice;
    private EditText etPairingCode;

    private BottomSheetDialog scannerDialog;
    private ExecutorService cameraExecutor;

    @Override
    public int getTheme() {
        return R.style.ModernBottomSheetDialogTheme;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_linked_devices, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvDeviceTitle = view.findViewById(R.id.tvDeviceTitle);
        tvDeviceSubtitle = view.findViewById(R.id.tvDeviceSubtitle);
        btnUnlinkDevice = view.findViewById(R.id.btnUnlinkDevice);
        etPairingCode = view.findViewById(R.id.etPairingCode);

        cameraExecutor = Executors.newSingleThreadExecutor();

        // 1. Load active linked companion state
        refreshLinkedDeviceState();

        // 2. Scan QR Code button
        view.findViewById(R.id.btnScanQr).setOnClickListener(v -> checkCameraAndOpenScanner());

        // 3. Connect via Manual Code / Peer ID
        view.findViewById(R.id.btnConnectCode).setOnClickListener(v -> {
            String input = etPairingCode.getText().toString().trim();
            if (input.isEmpty()) {
                Toast.makeText(requireContext(), "Please enter a Pairing Code or Web Peer ID", Toast.LENGTH_SHORT).show();
                return;
            }
            handleManualPairingCode(input);
        });

        // 4. Unlink device
        btnUnlinkDevice.setOnClickListener(v -> unlinkDesktopDevice());
    }

    private void refreshLinkedDeviceState() {
        if (getContext() == null) return;
        SharedPreferences prefs = requireContext().getSharedPreferences(PREF_LINKED_WEB, Context.MODE_PRIVATE);
        String savedSessionId = prefs.getString("sessionId", null);
        String savedWebPeerId = prefs.getString("webPeerId", null);
        long linkedAt = prefs.getLong("linkedAt", 0);

        if (savedSessionId != null || savedWebPeerId != null) {
            tvDeviceTitle.setText("💻 VEIL Web Companion (Linked)");
            String sub = "Active via WebRTC P2P DataChannels";
            if (savedWebPeerId != null) {
                sub += "\nPeer ID: " + savedWebPeerId;
            }
            tvDeviceSubtitle.setText(sub);
            btnUnlinkDevice.setVisibility(View.VISIBLE);
        } else {
            tvDeviceTitle.setText("No desktop companion linked");
            tvDeviceSubtitle.setText("Scan QR code on VEIL Web to link");
            btnUnlinkDevice.setVisibility(View.GONE);
        }
    }

    private void checkCameraAndOpenScanner() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1001);
            return;
        }
        openQrCameraScanner();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openQrCameraScanner();
        } else {
            Toast.makeText(requireContext(), "Camera permission required to scan QR code", Toast.LENGTH_SHORT).show();
        }
    }

    private void openQrCameraScanner() {
        if (getContext() == null) return;

        scannerDialog = new BottomSheetDialog(requireContext(), R.style.ModernBottomSheetDialogTheme);
        View scannerView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_qr_scanner, null);
        PreviewView previewView = scannerView.findViewById(R.id.cameraPreviewView);

        scannerView.findViewById(R.id.btnCancelScan).setOnClickListener(v -> scannerDialog.dismiss());

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext());
        AtomicBoolean isScanned = new AtomicBoolean(false);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                MultiFormatReader qrReader = new MultiFormatReader();

                imageAnalysis.setAnalyzer(cameraExecutor, (ImageProxy imageProxy) -> {
                    if (isScanned.get()) {
                        imageProxy.close();
                        return;
                    }

                    if (imageProxy.getFormat() == ImageFormat.YUV_420_888 || imageProxy.getFormat() == ImageFormat.YUV_422_888) {
                        ByteBuffer buffer = imageProxy.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);

                        int width = imageProxy.getWidth();
                        int height = imageProxy.getHeight();

                        PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                                bytes, width, height, 0, 0, width, height, false);
                        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

                        try {
                            Result result = qrReader.decode(bitmap);
                            if (result != null && result.getText() != null && !isScanned.getAndSet(true)) {
                                String qrText = result.getText();
                                requireActivity().runOnUiThread(() -> {
                                    if (scannerDialog != null && scannerDialog.isShowing()) {
                                        scannerDialog.dismiss();
                                    }
                                    handleScannedQrPayload(qrText);
                                });
                            }
                        } catch (Exception ignored) {
                            // QR not in current frame
                        }
                    }
                    imageProxy.close();
                });

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (Exception e) {
                Log.e(TAG, "Failed initializing CameraX scanner", e);
                Toast.makeText(requireContext(), "Camera scanner error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(requireContext()));

        scannerDialog.setContentView(scannerView);
        scannerDialog.show();
    }

    private void handleScannedQrPayload(String qrText) {
        try {
            JSONObject json = new JSONObject(qrText);
            String sessionId = json.optString("sessionId", "");
            String webPeerId = json.optString("webPeerId", "");
            String key = json.optString("key", "");

            if (sessionId.isEmpty() && webPeerId.isEmpty()) {
                Toast.makeText(requireContext(), "Unrecognized VEIL QR payload", Toast.LENGTH_SHORT).show();
                return;
            }

            confirmPairingWithWeb(sessionId, webPeerId, key);
        } catch (Exception e) {
            // Might be a raw sessionId string or URL
            if (qrText.contains("sessionId=")) {
                String sid = qrText.substring(qrText.indexOf("sessionId=") + 10);
                if (sid.contains("&")) sid = sid.substring(0, sid.indexOf("&"));
                confirmPairingWithWeb(sid, "", "");
            } else {
                Toast.makeText(requireContext(), "Invalid QR format: " + qrText, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void handleManualPairingCode(String code) {
        String trimmed = code.trim();
        String cleanDigits = trimmed.replaceAll("[\\s\\-]+", "");

        // 1. Check if user entered a 6-digit numeric pairing PIN (e.g. 582194)
        if (cleanDigits.matches("\\d{6}")) {
            Toast.makeText(requireContext(), "Looking up 6-digit PIN...", Toast.LENGTH_SHORT).show();
            FirebaseFirestore.getInstance().collection("pairing_sessions")
                    .whereEqualTo("pin", cleanDigits)
                    .whereEqualTo("status", "waiting")
                    .limit(1)
                    .get()
                    .addOnSuccessListener(querySnap -> {
                        if (querySnap != null && !querySnap.isEmpty()) {
                            com.google.firebase.firestore.DocumentSnapshot doc = querySnap.getDocuments().get(0);
                            String sid = doc.getId();
                            String wpid = doc.getString("webPeerId");
                            String key = doc.getString("key");
                            confirmPairingWithWeb(sid, wpid, key);
                        } else {
                            Toast.makeText(requireContext(), "PIN expired or invalid. Please check the code on your desktop screen.", Toast.LENGTH_LONG).show();
                        }
                    })
                    .addOnFailureListener(e -> {
                        Toast.makeText(requireContext(), "PIN lookup error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
            return;
        }

        // 2. Direct Web Peer ID input (e.g. veil_web_...)
        if (trimmed.startsWith("veil_web_")) {
            confirmPairingWithWeb("", trimmed, "");
            return;
        }

        // 3. Session ID input (e.g. veil_sess_...)
        confirmPairingWithWeb(trimmed, "", "");
    }

    private void confirmPairingWithWeb(String sessionId, String webPeerId, String ephemeralKey) {
        if (getContext() == null) return;

        Context ctx = requireContext();
        String currentUid = AuthManager.getInstance().getCurrentUserId(ctx);
        User currentUser = new UserPreferences(ctx).getCurrentUser();

        String username = currentUser != null && currentUser.getUsername() != null ? currentUser.getUsername() : "VeilMobile";
        String displayName = currentUser != null && currentUser.getDisplayName() != null ? currentUser.getDisplayName() : "VEIL Android";
        String publicKey = currentUser != null && currentUser.getPublicKey() != null ? currentUser.getPublicKey() : "";

        // If webPeerId is already present (e.g. from QR scan), write confirmation immediately!
        if (webPeerId != null && !webPeerId.isEmpty()) {
            writeHandshakeAndFinish(sessionId, webPeerId, currentUid, username, displayName, publicKey);
            return;
        }

        // If webPeerId was not in input (e.g. user typed only raw session ID), resolve via get()
        if (sessionId != null && !sessionId.isEmpty()) {
            FirebaseFirestore.getInstance()
                    .collection("pairing_sessions")
                    .document(sessionId)
                    .get()
                    .addOnSuccessListener(doc -> {
                        String resolvedPeerId = webPeerId;
                        if ((resolvedPeerId == null || resolvedPeerId.isEmpty()) && doc != null && doc.exists()) {
                            resolvedPeerId = doc.getString("webPeerId");
                        }
                        writeHandshakeAndFinish(sessionId, resolvedPeerId, currentUid, username, displayName, publicKey);
                    })
                    .addOnFailureListener(e -> {
                        writeHandshakeAndFinish(sessionId, webPeerId, currentUid, username, displayName, publicKey);
                    });
        } else {
            finishDevicePairing(sessionId, webPeerId);
        }
    }

    private void writeHandshakeAndFinish(String sessionId, String webPeerId, String currentUid, String username, String displayName, String publicKey) {
        if (sessionId != null && !sessionId.isEmpty()) {
            Map<String, Object> conf = new HashMap<>();
            conf.put("status", "confirmed");
            conf.put("sessionId", sessionId);
            conf.put("userId", currentUid);
            conf.put("mobilePeerId", currentUid);
            conf.put("username", username);
            conf.put("displayName", displayName);
            conf.put("publicKey", publicKey);
            conf.put("timestamp", System.currentTimeMillis());

            FirebaseFirestore.getInstance()
                    .collection("pairing_sessions")
                    .document(sessionId)
                    .set(conf)
                    .addOnSuccessListener(aVoid -> Log.i(TAG, "Pairing approved for session " + sessionId))
                    .addOnFailureListener(e -> Log.w(TAG, "Pairing write notice: " + e.getMessage()));
        }

        finishDevicePairing(sessionId, webPeerId);
    }

    private void finishDevicePairing(String sessionId, String webPeerId) {
        if (getContext() == null) return;
        Context ctx = requireContext();

        // 1. Establish direct WebRTC DataChannel to webPeerId
        if (webPeerId != null && !webPeerId.isEmpty()) {
            try {
                WebRtcManager.getInstance(ctx).getOrCreatePeerConnection(webPeerId, true);
                Log.i(TAG, "Direct WebRTC DataChannel connection initiated to desktop: " + webPeerId);
            } catch (Exception e) {
                Log.w(TAG, "Direct WebRTC DataChannel notice: " + e.getMessage());
            }
        }

        // 2. Persist active pairing locally
        SharedPreferences prefs = ctx.getSharedPreferences(PREF_LINKED_WEB, Context.MODE_PRIVATE);
        prefs.edit()
                .putString("sessionId", sessionId)
                .putString("webPeerId", webPeerId)
                .putLong("linkedAt", System.currentTimeMillis())
                .apply();

        Toast.makeText(ctx, "Desktop linked successfully! P2P channel established.", Toast.LENGTH_LONG).show();
        refreshLinkedDeviceState();
    }

    private void unlinkDesktopDevice() {
        if (getContext() == null) return;
        Context ctx = requireContext();
        SharedPreferences prefs = ctx.getSharedPreferences(PREF_LINKED_WEB, Context.MODE_PRIVATE);
        String sessionId = prefs.getString("sessionId", null);

        if (sessionId != null) {
            FirebaseFirestore.getInstance().collection("pairing_sessions").document(sessionId).delete();
        }

        prefs.edit().clear().apply();
        Toast.makeText(ctx, "Desktop device unlinked.", Toast.LENGTH_SHORT).show();
        refreshLinkedDeviceState();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}
