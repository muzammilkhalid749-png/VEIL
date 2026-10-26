package com.google.firebase.auth;

import android.net.Uri;

public class FirebaseUser {
    private final String uid;
    private final String phoneNumber;
    private final String displayName;
    private final String email;

    public FirebaseUser(String uid, String phoneNumber, String displayName, String email) {
        this.uid = uid;
        this.phoneNumber = phoneNumber;
        this.displayName = displayName;
        this.email = email;
    }

    public String getUid() {
        return uid;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getEmail() {
        return email;
    }

    public Uri getPhotoUrl() {
        return null;
    }
}
