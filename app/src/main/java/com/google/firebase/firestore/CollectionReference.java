package com.google.firebase.firestore;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;

import java.util.UUID;

public class CollectionReference extends Query {
    private final String id;

    public CollectionReference(String path) {
        super(path);
        this.id = FirebaseFirestore.extractDocId(this.collectionPath);
    }

    public String getId() {
        return id;
    }

    public String getPath() {
        return collectionPath;
    }

    public DocumentReference document() {
        return document(UUID.randomUUID().toString());
    }

    public DocumentReference document(String documentPath) {
        String sub = FirebaseFirestore.normalizePath(documentPath);
        if (sub.isEmpty()) {
            sub = UUID.randomUUID().toString();
        }
        return new DocumentReference(collectionPath.isEmpty() ? sub : collectionPath + "/" + sub);
    }

    public Task<DocumentReference> add(Object data) {
        final DocumentReference doc = document();
        final TaskCompletionSource<DocumentReference> tcs = new TaskCompletionSource<>();
        doc.set(data)
                .addOnSuccessListener(v -> tcs.trySetResult(doc))
                .addOnFailureListener(tcs::trySetException);
        return tcs.getTask();
    }
}
