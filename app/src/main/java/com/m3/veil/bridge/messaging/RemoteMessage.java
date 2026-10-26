package com.m3.veil.bridge.messaging;

import java.util.HashMap;
import java.util.Map;

public class RemoteMessage {
    private final Map<String, String> data = new HashMap<>();

    public static class Notification {
        public String getTitle() { return ""; }
        public String getBody() { return ""; }
    }

    public Notification getNotification() {
        return null;
    }

    public Map<String, String> getData() {
        return data;
    }
}
