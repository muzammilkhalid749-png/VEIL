package com.google.firebase.firestore;

public class FirebaseFirestore {
    private static final FirebaseFirestore INSTANCE = new FirebaseFirestore();

    public static FirebaseFirestore getInstance() {
        return INSTANCE;
    }

    public CollectionReference collection(String collectionPath) {
        return new CollectionReference(collectionPath);
    }

    public DocumentReference document(String documentPath) {
        return new DocumentReference(documentPath);
    }

    public WriteBatch batch() {
        return new WriteBatch();
    }
}
