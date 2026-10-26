package com.google.firebase.auth;

import androidx.annotation.NonNull;
import com.google.firebase.FirebaseException;

public class PhoneAuthProvider {
    public static class ForceResendingToken {}

    public static abstract class OnVerificationStateChangedCallbacks {
        public abstract void onVerificationCompleted(@NonNull PhoneAuthCredential credential);
        public abstract void onVerificationFailed(@NonNull FirebaseException e);
        public void onCodeSent(@NonNull String verificationId, @NonNull ForceResendingToken token) {}
    }

    public static PhoneAuthCredential getCredential(String verificationId, String smsCode) {
        return new PhoneAuthCredential(smsCode);
    }

    public static void verifyPhoneNumber(@NonNull PhoneAuthOptions options) {
        // Safe no-op or delegates to SovereignPhoneAuthBridge
    }
}
