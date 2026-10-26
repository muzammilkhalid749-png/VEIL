package com.google.firebase.firestore;

public class SetOptions {
    private static final SetOptions MERGE = new SetOptions();

    public static SetOptions merge() {
        return MERGE;
    }

    public static SetOptions mergeFields(String... fields) {
        return MERGE;
    }
}
