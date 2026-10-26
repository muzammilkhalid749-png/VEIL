package com.google.firebase.firestore;

public class FieldValue {
    public static Object serverTimestamp() {
        return new java.util.Date();
    }

    public static Object increment(long value) {
        return value;
    }

    public static Object increment(double value) {
        return value;
    }

    public static Object arrayUnion(Object... elements) {
        return elements;
    }

    public static Object arrayRemove(Object... elements) {
        return elements;
    }
}
