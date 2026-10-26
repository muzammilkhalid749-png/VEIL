package com.google.firebase.auth;

public class PhoneAuthCredential {
    private final String smsCode;

    public PhoneAuthCredential(String smsCode) {
        this.smsCode = smsCode;
    }

    public String getSmsCode() {
        return smsCode != null ? smsCode : "";
    }
}
