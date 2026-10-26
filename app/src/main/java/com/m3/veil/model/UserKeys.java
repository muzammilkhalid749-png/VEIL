package com.m3.veil.model;

import com.m3.veil.model.Timestamp;
import java.util.List;
import java.util.ArrayList;

public class UserKeys {
    private String publicKey;
    private String privateKeyEncrypted;
    private String deviceId;
    private Timestamp lastKeyRotation;
    private boolean fingerprintLock;
    private boolean faceLock;
    private boolean pinEnabled;
    private boolean multiDevice;
    private List<String> linkedDevices;

    public UserKeys() {
        // Default constructor for Firestore
    }

    public UserKeys(String userId) {
        // Generate random device ID
        this.deviceId = "DEVICE_" + generateRandomString(16);
        this.lastKeyRotation = Timestamp.now();
        this.fingerprintLock = true;
        this.faceLock = false;
        this.pinEnabled = false;
        this.multiDevice = false;
        this.linkedDevices = new ArrayList<>();

        // Keys will be generated locally and encrypted
        this.publicKey = "";
        this.privateKeyEncrypted = "";
    }

    private String generateRandomString(int length) {
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < length; i++) {
            result.append(characters.charAt((int) (Math.random() * characters.length())));
        }
        return result.toString();
    }

    // Getters and setters
    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }

    public String getPrivateKeyEncrypted() { return privateKeyEncrypted; }
    public void setPrivateKeyEncrypted(String privateKeyEncrypted) { this.privateKeyEncrypted = privateKeyEncrypted; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public Timestamp getLastKeyRotation() { return lastKeyRotation; }
    public void setLastKeyRotation(Timestamp lastKeyRotation) { this.lastKeyRotation = lastKeyRotation; }

    public boolean isFingerprintLock() { return fingerprintLock; }
    public void setFingerprintLock(boolean fingerprintLock) { this.fingerprintLock = fingerprintLock; }

    public boolean isFaceLock() { return faceLock; }
    public void setFaceLock(boolean faceLock) { this.faceLock = faceLock; }

    public boolean isPinEnabled() { return pinEnabled; }
    public void setPinEnabled(boolean pinEnabled) { this.pinEnabled = pinEnabled; }

    public boolean isMultiDevice() { return multiDevice; }
    public void setMultiDevice(boolean multiDevice) { this.multiDevice = multiDevice; }

    public List<String> getLinkedDevices() { return linkedDevices; }
    public void setLinkedDevices(List<String> linkedDevices) { this.linkedDevices = linkedDevices; }
}