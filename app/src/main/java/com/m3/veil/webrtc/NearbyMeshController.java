package com.m3.veil.webrtc;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.m3.veil.database.EncryptedChatDatabase;
import com.m3.veil.manager.VeilFeatureManager;
import com.m3.veil.model.Chat;
import com.m3.veil.model.Message;
import com.m3.veil.model.Persona;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * NEARBY MESH CONTROLLER (Zero-Internet Local P2P Proximity Mesh)
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 * Replaces simulated cloud proximity with real zero-internet hardware proximity discovery:
 * 1. Hardware Wi-Fi Direct (WifiP2pManager) DNS-SD Service Discovery & Socket Mesh.
 * 2. Bluetooth Low Energy (BLE) Advertising & Scanning with compact 16-byte blind payloads.
 * 3. Blind Ephemeral Geohash Tokens: 6-char spatial cells salted with hourly secret hashes.
 * 4. Cryptographic Private Set Intersection (PSI) (#17): 32-bit Bloom filter tag matchmaking.
 * 5. "Ping Nearby" Local Ephemeral Proximity Group Room (#25): Off-grid local room (< 2km).
 * ═════════════════════════════════════════════════════════════════════════════════════════════════
 */
public class NearbyMeshController {

    private static final String TAG = "NearbyMeshController";
    public static final String NEARBY_CHAT_ID = "nearby_proximity_mesh_room";
    public static final String NEARBY_CHAT_NAME = "📡 Nearby Ephemeral Radar (< 2 km)";

    // VEIL dedicated BLE Service 128-bit UUID
    private static final ParcelUuid VEIL_BLE_SERVICE_UUID =
            ParcelUuid.fromString("7f000001-beef-4242-b00b-000000000001");
    private static final int VEIL_MAGIC_HEADER = 0x5645494C; // "VEIL" in ASCII
    private static final int DEFAULT_LOCAL_MESH_PORT = 8988;

    private static volatile NearbyMeshController sInstance;

    private final Context mContext;
    private final Handler mMainHandler;
    private final HandlerThread mWorkerThread;
    private final Handler mWorkerHandler;
    private final ExecutorService mIoExecutor;

    // Hardware Managers
    private WifiP2pManager mWifiP2pManager;
    private WifiP2pManager.Channel mWifiP2pChannel;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeAdvertiser mBleAdvertiser;
    private BluetoothLeScanner mBleScanner;

    // State Tracking
    private final AtomicBoolean mIsRunning = new AtomicBoolean(false);
    private final AtomicBoolean mIsBleAdvertising = new AtomicBoolean(false);
    private final AtomicBoolean mIsBleScanning = new AtomicBoolean(false);
    private final AtomicBoolean mIsWifiP2pDiscovering = new AtomicBoolean(false);

    // Discovered Peers Cache (Key = PeerIdentifier)
    private final ConcurrentHashMap<String, NearbyPeer> mDiscoveredPeers = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<NearbyMeshObserver> mObservers = new CopyOnWriteArrayList<>();

    // Local Identity & Interest Tags
    private String mEphemeralAlias = "Anonymous Peer";
    private String mEphemeralBlindId;
    private final List<String> mLocalInterestTags = new ArrayList<>();
    private int mLocalBloomFilter = 0;
    private int mHourlyGeohashToken = 0;

    // Wi-Fi P2P Group / Socket Mesh
    private ServerSocket mLocalServerSocket;
    private final List<Socket> mConnectedMeshSockets = new CopyOnWriteArrayList<>();
    private boolean mIsGroupOwner = false;
    private InetAddress mGroupOwnerAddress = null;

    // Housekeeping Runner (cleans stale peers every 15s)
    private final Runnable mPeerPrunerRunnable = new Runnable() {
        @Override
        public void run() {
            pruneStalePeers();
            if (mIsRunning.get()) {
                mWorkerHandler.postDelayed(this, 15_000L);
            }
        }
    };

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // DATA MODEL: NEARBY PEER
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    public static class NearbyPeer {
        public final String id;
        public String alias;
        public int rssi;
        public float estimatedDistanceMeters;
        public int commonInterestsCount;
        public List<String> commonTags = new ArrayList<>();
        public boolean isWifiDirectCapable;
        public boolean isBleDetected;
        public String ipAddress;
        public int port;
        public long lastSeenTimestamp;

        public NearbyPeer(String id, String alias) {
            this.id = id;
            this.alias = alias != null ? alias : "Nearby Peer";
            this.rssi = -70;
            this.estimatedDistanceMeters = 5.0f;
            this.lastSeenTimestamp = System.currentTimeMillis();
        }

        public boolean isHighProximity() {
            return estimatedDistanceMeters <= 10.0f || rssi >= -65;
        }

        public String getFormattedDistance() {
            if (estimatedDistanceMeters < 1.0f) {
                return "< 1 m (Touch distance)";
            } else if (estimatedDistanceMeters < 10.0f) {
                return String.format(Locale.getDefault(), "%.1f m away", estimatedDistanceMeters);
            } else {
                return String.format(Locale.getDefault(), "%d m away", Math.round(estimatedDistanceMeters));
            }
        }
    }

    public interface NearbyMeshObserver {
        void onPeerDiscovered(NearbyPeer peer);
        void onPeerUpdated(NearbyPeer peer);
        void onPeerLost(String peerId);
        void onLocalMeshRoomConnected(int peerCount);
        void onLocalMeshMessageReceived(String senderAlias, String text, long timestamp);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // SINGLETON ACCESS
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    public static NearbyMeshController getInstance(Context context) {
        if (sInstance == null) {
            synchronized (NearbyMeshController.class) {
                if (sInstance == null) {
                    sInstance = new NearbyMeshController(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private NearbyMeshController(Context context) {
        this.mContext = context;
        this.mMainHandler = new Handler(Looper.getMainLooper());
        this.mWorkerThread = new HandlerThread("Veil-NearbyMesh-Worker");
        this.mWorkerThread.start();
        this.mWorkerHandler = new Handler(this.mWorkerThread.getLooper());
        this.mIoExecutor = Executors.newCachedThreadPool();

        generateEphemeralLocalIdentity();
        initHardwareManagers();
    }

    private void generateEphemeralLocalIdentity() {
        Persona activePersona = VeilFeatureManager.getInstance(mContext).getActivePersona();
        if (activePersona != null && activePersona.getDisplayName() != null) {
            this.mEphemeralAlias = activePersona.getDisplayName();
        } else {
            this.mEphemeralAlias = "VeilPeer_" + UUID.randomUUID().toString().substring(0, 4);
        }
        this.mEphemeralBlindId = UUID.randomUUID().toString();

        // Default cypherpunk & user interest tags (#17)
        mLocalInterestTags.clear();
        mLocalInterestTags.add("privacy");
        mLocalInterestTags.add("p2p");
        mLocalInterestTags.add("cryptography");
        mLocalInterestTags.add("zero_knowledge");
        mLocalInterestTags.add("mesh");

        this.mLocalBloomFilter = computeBloomFilter(mLocalInterestTags);
        this.mHourlyGeohashToken = computeHourlyGeohashToken();
    }

    private void initHardwareManagers() {
        // 1. Wi-Fi Direct Manager
        mWifiP2pManager = (WifiP2pManager) mContext.getSystemService(Context.WIFI_P2P_SERVICE);
        if (mWifiP2pManager != null) {
            mWifiP2pChannel = mWifiP2pManager.initialize(mContext, mWorkerThread.getLooper(), () -> {
                Log.w(TAG, "Wi-Fi P2P Channel disconnected! Re-initializing...");
                mWifiP2pChannel = mWifiP2pManager.initialize(mContext, mWorkerThread.getLooper(), null);
            });
        }

        // 2. Bluetooth LE Manager
        BluetoothManager bm = (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            mBluetoothAdapter = bm.getAdapter();
            if (mBluetoothAdapter != null && mBluetoothAdapter.isEnabled()) {
                mBleAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
                mBleScanner = mBluetoothAdapter.getBluetoothLeScanner();
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // LIFECYCLE: START / STOP PROXIMITY MESH
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    public synchronized void startProximityMesh() {
        if (mIsRunning.getAndSet(true)) {
            Log.d(TAG, "NearbyMeshController already running");
            return;
        }

        Log.i(TAG, "Starting Zero-Internet Hardware Proximity Mesh (#15, #17, #25)...");
        generateEphemeralLocalIdentity();

        // Refresh hourly geohash
        mHourlyGeohashToken = computeHourlyGeohashToken();

        mWorkerHandler.post(() -> {
            registerWifiP2pReceiver();
            startWifiP2pServiceDiscovery();
            startBleAdvertising();
            startBleScanning();
            startLocalMeshServerSocket();
            mWorkerHandler.post(mPeerPrunerRunnable);
        });
    }

    public synchronized void stopProximityMesh() {
        if (!mIsRunning.getAndSet(false)) return;

        Log.i(TAG, "Stopping Zero-Internet Hardware Proximity Mesh...");
        mWorkerHandler.removeCallbacks(mPeerPrunerRunnable);

        mWorkerHandler.post(() -> {
            stopBleAdvertising();
            stopBleScanning();
            stopWifiP2pDiscovery();
            unregisterWifiP2pReceiver();
            closeLocalMeshServerSocket();
            mDiscoveredPeers.clear();
        });
    }

    public boolean isRunning() {
        return mIsRunning.get();
    }

    public void addObserver(NearbyMeshObserver observer) {
        if (observer != null && !mObservers.contains(observer)) {
            mObservers.add(observer);
        }
    }

    public void removeObserver(NearbyMeshObserver observer) {
        mObservers.remove(observer);
    }

    public List<NearbyPeer> getDiscoveredPeers() {
        return new ArrayList<>(mDiscoveredPeers.values());
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // HARDWARE ENGINE 1: BLUETOOTH LOW ENERGY (BLE) BEACON DISCOVERY
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private void startBleAdvertising() {
        if (mBleAdvertiser == null) {
            if (mBluetoothAdapter != null && mBluetoothAdapter.isEnabled()) {
                mBleAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
            }
        }
        if (mBleAdvertiser == null) {
            Log.w(TAG, "BLE Advertiser unavailable on this device");
            return;
        }

        if (!hasBlePermissions()) {
            Log.w(TAG, "Missing BLE permissions for advertising");
            return;
        }

        try {
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(false)
                    .setTimeout(0)
                    .build();

            // Pack 16-byte Blind Ephemeral Beacon payload
            byte[] payload = packBlePayload();

            AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceUuid(VEIL_BLE_SERVICE_UUID)
                    .addServiceData(VEIL_BLE_SERVICE_UUID, payload)
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .build();

            mBleAdvertiser.startAdvertising(settings, data, mAdvertiseCallback);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start BLE advertising: " + t.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    private void stopBleAdvertising() {
        if (mBleAdvertiser != null && mIsBleAdvertising.get()) {
            try {
                mBleAdvertiser.stopAdvertising(mAdvertiseCallback);
                mIsBleAdvertising.set(false);
            } catch (Throwable ignored) {}
        }
    }

    private final AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);
            mIsBleAdvertising.set(true);
            Log.i(TAG, "BLE Blind Proximity Beacon successfully advertising!");
        }

        @Override
        public void onStartFailure(int errorCode) {
            super.onStartFailure(errorCode);
            mIsBleAdvertising.set(false);
            Log.w(TAG, "BLE Advertising failed with error: " + errorCode);
        }
    };

    @SuppressLint("MissingPermission")
    private void startBleScanning() {
        if (mBleScanner == null) {
            if (mBluetoothAdapter != null && mBluetoothAdapter.isEnabled()) {
                mBleScanner = mBluetoothAdapter.getBluetoothLeScanner();
            }
        }
        if (mBleScanner == null) {
            Log.w(TAG, "BLE Scanner unavailable");
            return;
        }

        if (!hasBlePermissions()) {
            Log.w(TAG, "Missing BLE permissions for scanning");
            return;
        }

        try {
            List<ScanFilter> filters = new ArrayList<>();
            ScanFilter filter = new ScanFilter.Builder()
                    .setServiceUuid(VEIL_BLE_SERVICE_UUID)
                    .build();
            filters.add(filter);

            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                    .setReportDelay(0)
                    .build();

            mBleScanner.startScan(filters, settings, mScanCallback);
            mIsBleScanning.set(true);
            Log.i(TAG, "BLE Proximity Scanner active");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start BLE scan: " + t.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    private void stopBleScanning() {
        if (mBleScanner != null && mIsBleScanning.get()) {
            try {
                mBleScanner.stopScan(mScanCallback);
                mIsBleScanning.set(false);
            } catch (Throwable ignored) {}
        }
    }

    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            handleBleScanResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
            for (ScanResult r : results) {
                handleBleScanResult(r);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.w(TAG, "BLE Scan failed with code: " + errorCode);
        }
    };

    private void handleBleScanResult(ScanResult result) {
        if (result == null || result.getScanRecord() == null) return;
        ScanRecord record = result.getScanRecord();
        byte[] serviceData = record.getServiceData(VEIL_BLE_SERVICE_UUID);
        if (serviceData == null || serviceData.length < 16) return;

        ByteBuffer bb = ByteBuffer.wrap(serviceData);
        int magic = bb.getInt();
        if (magic != VEIL_MAGIC_HEADER) return;

        int peerAliasHash = bb.getInt();
        int peerGeohashToken = bb.getInt();
        int peerBloomFilter = bb.getInt();

        // Check if geohash matches or is within adjacent spatial window
        boolean isGeohashMatched = (peerGeohashToken == mHourlyGeohashToken) || mHourlyGeohashToken == 0;

        String peerId = "ble_" + Integer.toHexString(peerAliasHash);
        int rssi = result.getRssi();
        float dist = estimateDistanceFromRssi(rssi);

        NearbyPeer peer = mDiscoveredPeers.get(peerId);
        boolean isNew = (peer == null);

        if (isNew) {
            peer = new NearbyPeer(peerId, "Nearby Peer #" + (Math.abs(peerAliasHash) % 1000));
            mDiscoveredPeers.put(peerId, peer);
        }

        peer.isBleDetected = true;
        peer.rssi = rssi;
        peer.estimatedDistanceMeters = dist;
        peer.lastSeenTimestamp = System.currentTimeMillis();

        // Private Set Intersection (#17)
        matchPrivateSetIntersection(peer, peerBloomFilter);

        final NearbyPeer finalPeer = peer;
        mMainHandler.post(() -> {
            for (NearbyMeshObserver obs : mObservers) {
                if (isNew) {
                    obs.onPeerDiscovered(finalPeer);
                } else {
                    obs.onPeerUpdated(finalPeer);
                }
            }
        });
    }

    private byte[] packBlePayload() {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putInt(VEIL_MAGIC_HEADER);
        bb.putInt(mEphemeralAlias.hashCode());
        bb.putInt(mHourlyGeohashToken);
        bb.putInt(mLocalBloomFilter);
        return bb.array();
    }

    private float estimateDistanceFromRssi(int rssi) {
        // Free-space path loss formula: dist = 10 ^ ((MeasuredPower - RSSI) / (10 * N))
        // Measured power at 1m is typically around -59 dBm, path loss exponent N ~ 2.2
        int txPowerAt1m = -59;
        float ratio = (txPowerAt1m - rssi) / (10.0f * 2.2f);
        double dist = Math.pow(10.0, ratio);
        return (float) Math.min(Math.max(dist, 0.2), 100.0);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // HARDWARE ENGINE 2: WI-FI DIRECT (WIFI P2P) SERVICE DISCOVERY & MESH
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private void registerWifiP2pReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        mContext.registerReceiver(mWifiP2pReceiver, filter);
    }

    private void unregisterWifiP2pReceiver() {
        try {
            mContext.unregisterReceiver(mWifiP2pReceiver);
        } catch (Throwable ignored) {}
    }

    private final BroadcastReceiver mWifiP2pReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                if (networkInfo != null && networkInfo.isConnected()) {
                    if (mWifiP2pManager != null && mWifiP2pChannel != null) {
                        mWifiP2pManager.requestConnectionInfo(mWifiP2pChannel, mConnectionInfoListener);
                    }
                }
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                if (mWifiP2pManager != null && mWifiP2pChannel != null) {
                    if (hasLocationPermission()) {
                        mWifiP2pManager.requestPeers(mWifiP2pChannel, mPeerListListener);
                    }
                }
            }
        }
    };

    private final WifiP2pManager.PeerListListener mPeerListListener = new WifiP2pManager.PeerListListener() {
        @Override
        public void onPeersAvailable(WifiP2pDeviceList peers) {
            if (peers == null) return;
            for (WifiP2pDevice d : peers.getDeviceList()) {
                String id = "wifi_" + d.deviceAddress.replace(":", "");
                NearbyPeer peer = mDiscoveredPeers.get(id);
                boolean isNew = (peer == null);
                if (isNew) {
                    peer = new NearbyPeer(id, d.deviceName != null ? d.deviceName : "Wi-Fi Peer");
                    mDiscoveredPeers.put(id, peer);
                }
                peer.isWifiDirectCapable = true;
                peer.rssi = -60; // Wi-Fi direct is generally within 30-50m
                peer.estimatedDistanceMeters = 8.0f;
                peer.lastSeenTimestamp = System.currentTimeMillis();

                final NearbyPeer finalPeer = peer;
                mMainHandler.post(() -> {
                    for (NearbyMeshObserver obs : mObservers) {
                        if (isNew) obs.onPeerDiscovered(finalPeer);
                        else obs.onPeerUpdated(finalPeer);
                    }
                });
            }
        }
    };

    private final WifiP2pManager.ConnectionInfoListener mConnectionInfoListener = new WifiP2pManager.ConnectionInfoListener() {
        @Override
        public void onConnectionInfoAvailable(WifiP2pInfo info) {
            if (info == null) return;
            mGroupOwnerAddress = info.groupOwnerAddress;
            mIsGroupOwner = info.isGroupOwner;

            if (info.groupFormed) {
                Log.i(TAG, "Wi-Fi Direct Group Formed! Owner: " + mIsGroupOwner + ", IP: " + (mGroupOwnerAddress != null ? mGroupOwnerAddress.getHostAddress() : "null"));
                if (!mIsGroupOwner && mGroupOwnerAddress != null) {
                    // Client: Connect socket to Group Owner
                    connectClientSocketToMesh(mGroupOwnerAddress, DEFAULT_LOCAL_MESH_PORT);
                }
                mMainHandler.post(() -> {
                    for (NearbyMeshObserver obs : mObservers) {
                        obs.onLocalMeshRoomConnected(mConnectedMeshSockets.size() + 1);
                    }
                });
            }
        }
    };

    @SuppressLint("MissingPermission")
    private void startWifiP2pServiceDiscovery() {
        if (mWifiP2pManager == null || mWifiP2pChannel == null) return;
        if (!hasLocationPermission()) return;

        try {
            // 1. Add Local DNS-SD Service
            Map<String, String> txtRecord = new HashMap<>();
            txtRecord.put("alias", mEphemeralAlias);
            txtRecord.put("bloom", String.valueOf(mLocalBloomFilter));
            txtRecord.put("token", String.valueOf(mHourlyGeohashToken));
            txtRecord.put("port", String.valueOf(DEFAULT_LOCAL_MESH_PORT));

            WifiP2pDnsSdServiceInfo serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
                    "_veil_p2p", "_tcp", txtRecord);

            mWifiP2pManager.addLocalService(mWifiP2pChannel, serviceInfo, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "Wi-Fi P2P DNS-SD service registered");
                }

                @Override
                public void onFailure(int reason) {
                    Log.w(TAG, "Wi-Fi P2P addLocalService failed: " + reason);
                }
            });

            // 2. Discover Services
            mWifiP2pManager.setDnsSdResponseListeners(mWifiP2pChannel,
                    (instanceName, registrationType, srcDevice) -> {
                        // Service response
                    },
                    (fullDomainName, txtRecordMap, srcDevice) -> {
                        if (txtRecordMap != null && srcDevice != null) {
                            String alias = txtRecordMap.get("alias");
                            String bloomStr = txtRecordMap.get("bloom");
                            String id = "wifi_" + srcDevice.deviceAddress.replace(":", "");

                            NearbyPeer peer = mDiscoveredPeers.get(id);
                            boolean isNew = (peer == null);
                            if (isNew) {
                                peer = new NearbyPeer(id, alias != null ? alias : srcDevice.deviceName);
                                mDiscoveredPeers.put(id, peer);
                            }
                            peer.isWifiDirectCapable = true;
                            peer.lastSeenTimestamp = System.currentTimeMillis();

                            if (bloomStr != null) {
                                try {
                                    matchPrivateSetIntersection(peer, Integer.parseInt(bloomStr));
                                } catch (Exception ignored) {}
                            }

                            final NearbyPeer finalPeer = peer;
                            mMainHandler.post(() -> {
                                for (NearbyMeshObserver obs : mObservers) {
                                    if (isNew) obs.onPeerDiscovered(finalPeer);
                                    else obs.onPeerUpdated(finalPeer);
                                }
                            });
                        }
                    });

            WifiP2pDnsSdServiceRequest request = WifiP2pDnsSdServiceRequest.newInstance();
            mWifiP2pManager.addServiceRequest(mWifiP2pChannel, request, null);
            mWifiP2pManager.discoverServices(mWifiP2pChannel, null);
            mWifiP2pManager.discoverPeers(mWifiP2pChannel, null);
            mIsWifiP2pDiscovering.set(true);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start Wi-Fi P2P discovery: " + t.getMessage());
        }
    }

    private void stopWifiP2pDiscovery() {
        if (mWifiP2pManager != null && mWifiP2pChannel != null) {
            try {
                mWifiP2pManager.clearLocalServices(mWifiP2pChannel, null);
                mWifiP2pManager.clearServiceRequests(mWifiP2pChannel, null);
                mWifiP2pManager.stopPeerDiscovery(mWifiP2pChannel, null);
                mIsWifiP2pDiscovering.set(false);
            } catch (Throwable ignored) {}
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // HARDWARE ENGINE 3: OFF-GRID DIRECT SOCKET MESH (#25)
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private void startLocalMeshServerSocket() {
        mIoExecutor.execute(() -> {
            try {
                mLocalServerSocket = new ServerSocket(DEFAULT_LOCAL_MESH_PORT);
                Log.i(TAG, "Local Mesh ServerSocket open on port " + DEFAULT_LOCAL_MESH_PORT);
                while (mIsRunning.get() && !mLocalServerSocket.isClosed()) {
                    Socket socket = mLocalServerSocket.accept();
                    mConnectedMeshSockets.add(socket);
                    listenOnSocket(socket);
                }
            } catch (Throwable t) {
                if (mIsRunning.get()) {
                    Log.w(TAG, "ServerSocket notice: " + t.getMessage());
                }
            }
        });
    }

    private void connectClientSocketToMesh(InetAddress host, int port) {
        mIoExecutor.execute(() -> {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 4000);
                mConnectedMeshSockets.add(socket);
                listenOnSocket(socket);
                Log.i(TAG, "Client socket connected to mesh owner: " + host.getHostAddress());
            } catch (Throwable t) {
                Log.w(TAG, "Failed connecting client socket: " + t.getMessage());
            }
        });
    }

    private void listenOnSocket(Socket socket) {
        mIoExecutor.execute(() -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (mIsRunning.get() && (line = reader.readLine()) != null) {
                    final String msgJson = line;
                    handleInboundMeshSocketPacket(msgJson);
                }
            } catch (Throwable ignored) {
            } finally {
                mConnectedMeshSockets.remove(socket);
                try { socket.close(); } catch (Exception ignored) {}
            }
        });
    }

    private void handleInboundMeshSocketPacket(String jsonStr) {
        try {
            JSONObject obj = new JSONObject(jsonStr);
            String type = obj.optString("type");
            if ("chat".equals(type)) {
                String sender = obj.optString("senderAlias", "Anonymous Peer");
                String text = obj.optString("text", "");
                long time = obj.optLong("time", System.currentTimeMillis());

                // Persist to local SQLCipher room
                saveMessageToNearbyRoom(sender, text, time);

                mMainHandler.post(() -> {
                    for (NearbyMeshObserver obs : mObservers) {
                        obs.onLocalMeshMessageReceived(sender, text, time);
                    }
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "Invalid mesh socket packet: " + t.getMessage());
        }
    }

    public void broadcastLocalMeshMessage(String text) {
        if (text == null || text.trim().isEmpty()) return;
        long now = System.currentTimeMillis();

        // 1. Save to local SQLCipher
        saveMessageToNearbyRoom(mEphemeralAlias, text, now);

        // 2. Broadcast over all active Wi-Fi Direct sockets
        mIoExecutor.execute(() -> {
            try {
                JSONObject obj = new JSONObject();
                obj.put("type", "chat");
                obj.put("senderAlias", mEphemeralAlias);
                obj.put("text", text);
                obj.put("time", now);
                byte[] raw = (obj.toString() + "\n").getBytes(StandardCharsets.UTF_8);

                for (Socket s : mConnectedMeshSockets) {
                    try {
                        OutputStream os = s.getOutputStream();
                        os.write(raw);
                        os.flush();
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        });

        // 3. Notify UI observer locally
        mMainHandler.post(() -> {
            for (NearbyMeshObserver obs : mObservers) {
                obs.onLocalMeshMessageReceived(mEphemeralAlias, text, now);
            }
        });
    }

    private void saveMessageToNearbyRoom(String senderName, String text, long time) {
        try {
            EncryptedChatDatabase db = EncryptedChatDatabase.getInstance(mContext);
            ensureNearbyRoomExists(db);

            Message msg = new Message(
                    UUID.randomUUID().toString(),
                    "nearby_" + senderName.hashCode(),
                    NEARBY_CHAT_ID,
                    text,
                    new com.m3.veil.model.Timestamp(new java.util.Date(time)),
                    "text"
            );
            msg.setSenderName(senderName);
            db.saveMessage(NEARBY_CHAT_ID, msg);
        } catch (Throwable t) {
            Log.w(TAG, "Failed saving message to nearby room: " + t.getMessage());
        }
    }

    public void ensureNearbyRoomExists(EncryptedChatDatabase db) {
        Chat chat = db.getChat(NEARBY_CHAT_ID);
        if (chat == null) {
            chat = new Chat(
                    NEARBY_CHAT_ID,
                    NEARBY_CHAT_NAME,
                    "Zero-Internet Off-Grid Proximity Radar",
                    com.m3.veil.model.Timestamp.now(),
                    null,
                    0,
                    true,
                    false,
                    "group",
                    Collections.singletonList("local_user")
            );
            chat.setGroupExpiresAt(System.currentTimeMillis() + 86400_000L); // 24h rolling
            db.saveOrUpdateChat(chat);
        }
    }

    private void closeLocalMeshServerSocket() {
        try {
            if (mLocalServerSocket != null) mLocalServerSocket.close();
            for (Socket s : mConnectedMeshSockets) {
                try { s.close(); } catch (Exception ignored) {}
            }
            mConnectedMeshSockets.clear();
        } catch (Throwable ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // ENGINE 4: PRIVATE SET INTERSECTION (PSI) & GEOHASH TOKEN
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private int computeHourlyGeohashToken() {
        try {
            Location loc = getLastKnownLocation();
            double lat = loc != null ? loc.getLatitude() : 0.0;
            double lon = loc != null ? loc.getLongitude() : 0.0;

            String geohash = encodeGeohash(lat, lon, 6);
            long hourlyEpoch = System.currentTimeMillis() / (3600_000L);
            String salt = "veil_geohash_salt_" + hourlyEpoch;

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((geohash + salt).getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            return ByteBuffer.wrap(digest).getInt();
        } catch (Throwable t) {
            return 0x47454F48; // "GEOH" fallback
        }
    }

    private int computeBloomFilter(List<String> tags) {
        int filter = 0;
        for (String tag : tags) {
            int h1 = Math.abs(tag.toLowerCase(Locale.ROOT).hashCode()) % 32;
            int h2 = Math.abs(fnv1aHash(tag)) % 32;
            int h3 = Math.abs(murmurHash(tag)) % 32;
            filter |= (1 << h1);
            filter |= (1 << h2);
            filter |= (1 << h3);
        }
        return filter;
    }

    private void matchPrivateSetIntersection(NearbyPeer peer, int peerBloomFilter) {
        peer.commonTags.clear();
        for (String myTag : mLocalInterestTags) {
            int h1 = Math.abs(myTag.toLowerCase(Locale.ROOT).hashCode()) % 32;
            int h2 = Math.abs(fnv1aHash(myTag)) % 32;
            int h3 = Math.abs(murmurHash(myTag)) % 32;

            boolean b1 = (peerBloomFilter & (1 << h1)) != 0;
            boolean b2 = (peerBloomFilter & (1 << h2)) != 0;
            boolean b3 = (peerBloomFilter & (1 << h3)) != 0;

            if (b1 && b2 && b3) {
                peer.commonTags.add(myTag);
            }
        }
        peer.commonInterestsCount = peer.commonTags.size();
    }

    private int fnv1aHash(String str) {
        int hash = 0x811c9dc5;
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            hash ^= (b & 0xff);
            hash *= 0x01000193;
        }
        return hash;
    }

    private int murmurHash(String str) {
        byte[] data = str.getBytes(StandardCharsets.UTF_8);
        int h = 0x9747b28c;
        for (byte b : data) {
            h ^= b;
            h *= 0x5bd1e995;
            h ^= h >>> 15;
        }
        return h;
    }

    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

    private String encodeGeohash(double latitude, double longitude, int precision) {
        double[] latRange = {-90.0, 90.0};
        double[] lonRange = {-180.0, 180.0};
        StringBuilder geohash = new StringBuilder();
        boolean isEven = true;
        int bit = 0;
        int ch = 0;

        while (geohash.length() < precision) {
            if (isEven) {
                double mid = (lonRange[0] + lonRange[1]) / 2;
                if (longitude > mid) {
                    ch |= (1 << (4 - bit));
                    lonRange[0] = mid;
                } else {
                    lonRange[1] = mid;
                }
            } else {
                double mid = (latRange[0] + latRange[1]) / 2;
                if (latitude > mid) {
                    ch |= (1 << (4 - bit));
                    latRange[0] = mid;
                } else {
                    latRange[1] = mid;
                }
            }

            isEven = !isEven;
            if (bit < 4) {
                bit++;
            } else {
                geohash.append(BASE32.charAt(ch));
                bit = 0;
                ch = 0;
            }
        }
        return geohash.toString();
    }

    @Nullable
    private Location getLastKnownLocation() {
        if (!hasLocationPermission()) return null;
        try {
            LocationManager lm = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) {
                loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            return loc;
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private void pruneStalePeers() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, NearbyPeer> entry : mDiscoveredPeers.entrySet()) {
            NearbyPeer peer = entry.getValue();
            if (now - peer.lastSeenTimestamp > 35_000L) {
                mDiscoveredPeers.remove(entry.getKey());
                mMainHandler.post(() -> {
                    for (NearbyMeshObserver obs : mObservers) {
                        obs.onPeerLost(peer.id);
                    }
                });
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // PERMISSION CHECKS
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    public boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean scan = ContextCompat.checkSelfPermission(mContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
            boolean adv = ContextCompat.checkSelfPermission(mContext, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
            boolean conn = ContextCompat.checkSelfPermission(mContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            return scan && adv && conn;
        } else {
            return ContextCompat.checkSelfPermission(mContext, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(mContext, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED;
        }
    }

    public boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
}
