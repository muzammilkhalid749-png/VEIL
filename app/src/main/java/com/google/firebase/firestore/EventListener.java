package com.google.firebase.firestore;

public interface EventListener<T> {
    void onEvent(T value, FirebaseFirestoreException error);
}
