package com.m3.veil.bridge.firestore;

public class DocumentChange {
    public enum Type {
        ADDED,
        MODIFIED,
        REMOVED
    }

    private final DocumentSnapshot document;
    private final Type type;

    public DocumentChange(DocumentSnapshot document, Type type) {
        this.document = document;
        this.type = type;
    }

    public Type getType() {
        return type;
    }

    public DocumentSnapshot getDocument() {
        return document;
    }

    public int getOldIndex() {
        return 0;
    }

    public int getNewIndex() {
        return 0;
    }
}
