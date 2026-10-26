package com.google.firebase.messaging;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;

public class FirebaseMessaging {
    private static final FirebaseMessaging INSTANCE = new FirebaseMessaging();

    public static FirebaseMessaging getInstance() {
        return INSTANCE;
    }

    public Task<String> getToken() {
        return Tasks.forResult("sovereign_local_token");
    }
}
