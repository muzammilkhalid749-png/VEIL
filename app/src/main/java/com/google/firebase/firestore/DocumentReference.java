package com.google.firebase.firestore;

import com.google.android.gms.tasks.Task;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

public class DocumentReference {
    private final String path;
    private final String id;

    public DocumentReference(String path) {
        this.path = FirebaseFirestore.normalizePath(path);
        this.id = FirebaseFirestore.extractDocId(this.path);
    }

    public String getId() {
        return id;
    }

    public String getPath() {
        return path;
    }

    public CollectionReference collection(String collectionPath) {
        String sub = FirebaseFirestore.normalizePath(collectionPath);
        return new CollectionReference(path.isEmpty() ? sub : path + "/" + sub);
    }

    public Task<DocumentSnapshot> get() {
        return FirebaseFirestore.getInstance().getDocumentAsync(path);
    }

    public Task<Void> set(Object data) {
        return FirebaseFirestore.getInstance().setDocumentAsync(path, data, false);
    }

    public Task<Void> set(Object data, SetOptions options) {
        return FirebaseFirestore.getInstance().setDocumentAsync(path, data, true);
    }

    public Task<Void> update(Map<String, Object> data) {
        return FirebaseFirestore.getInstance().setDocumentAsync(path, data, true);
    }

    public Task<Void> update(String field, Object value, Object... moreFieldsAndValues) {
        Map<String, Object> map = new HashMap<>();
        if (field != null) {
            map.put(field, value);
        }
        if (moreFieldsAndValues != null) {
            for (int i = 0; i + 1 < moreFieldsAndValues.length; i += 2) {
                Object k = moreFieldsAndValues[i];
                Object v = moreFieldsAndValues[i + 1];
                if (k != null) {
                    map.put(String.valueOf(k), v);
                }
            }
        }
        return FirebaseFirestore.getInstance().setDocumentAsync(path, map, true);
    }

    public Task<Void> delete() {
        return FirebaseFirestore.getInstance().deleteDocumentAsync(path);
    }

    public ListenerRegistration addSnapshotListener(EventListener<DocumentSnapshot> listener) {
        return FirebaseFirestore.getInstance().registerDocumentListener(path, listener);
    }

    public ListenerRegistration addSnapshotListener(MetadataChanges metadata, EventListener<DocumentSnapshot> listener) {
        return addSnapshotListener(listener);
    }

    public ListenerRegistration addSnapshotListener(Executor executor, EventListener<DocumentSnapshot> listener) {
        return addSnapshotListener(listener);
    }

    public ListenerRegistration addSnapshotListener(android.app.Activity activity, EventListener<DocumentSnapshot> listener) {
        return addSnapshotListener(listener);
    }

    public ListenerRegistration addSnapshotListener(android.app.Activity activity, MetadataChanges metadata, EventListener<DocumentSnapshot> listener) {
        return addSnapshotListener(listener);
    }
}
