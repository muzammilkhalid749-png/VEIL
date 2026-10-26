package com.google.firebase.auth;

import android.app.Activity;
import androidx.annotation.NonNull;
import java.util.concurrent.TimeUnit;

public class PhoneAuthOptions {
    public static Builder newBuilder() {
        return new Builder();
    }

    public static Builder newBuilder(FirebaseAuth auth) {
        return new Builder();
    }

    public static class Builder {
        public Builder setPhoneNumber(@NonNull String phoneNumber) { return this; }
        public Builder setTimeout(@NonNull Long timeout, @NonNull TimeUnit unit) { return this; }
        public Builder setActivity(@NonNull Activity activity) { return this; }
        public Builder setCallbacks(@NonNull PhoneAuthProvider.OnVerificationStateChangedCallbacks callbacks) { return this; }
        public Builder setForceResendingToken(@NonNull PhoneAuthProvider.ForceResendingToken token) { return this; }
        public PhoneAuthOptions build() { return new PhoneAuthOptions(); }
    }
}
