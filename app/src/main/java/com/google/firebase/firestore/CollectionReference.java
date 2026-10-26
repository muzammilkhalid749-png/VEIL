package com.google.firebase.firestore;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import java.util.UUID;

public class CollectionReference extends Query {
    private final String id;

    public CollectionReference(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public DocumentReference document() {
        return new DocumentReference(UUID.randomUUID().toString());
    }

    public DocumentReference document(String documentPath) {
        return new DocumentReference(documentPath);
    }

    public Task<DocumentReference> add(Object data) {
        DocumentReference doc = document();
        return Tasks.forResult(doc);
    }
}
