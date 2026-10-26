package com.m3.veil.bridge.messaging;

import com.m3.veil.bridge.tasks.Task;
import com.m3.veil.bridge.tasks.Tasks;

public class FirebaseMessaging {
    private static final FirebaseMessaging INSTANCE = new FirebaseMessaging();

    public static FirebaseMessaging getInstance() {
        return INSTANCE;
    }

    public Task<String> getToken() {
        return Tasks.forResult("sovereign_local_token");
    }
}
