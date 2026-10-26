package com.m3.veil.bridge.firestore;

public interface EventListener<T> {
    void onEvent(T value, FirebaseFirestoreException error);
}
