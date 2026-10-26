package com.m3.veil.bridge.firestore;

import com.m3.veil.bridge.tasks.Task;
import com.m3.veil.bridge.tasks.Tasks;
import java.util.Map;
import java.util.concurrent.Executor;

public class DocumentReference {
    private final String id;

    public DocumentReference(String id) {
        this.id = id != null ? id : "";
    }

    public String getId() {
        return id;
    }

    public String getPath() {
        return id;
    }

    public CollectionReference collection(String collectionPath) {
        return new CollectionReference(collectionPath);
    }

    public Task<DocumentSnapshot> get() {
        return Tasks.forResult(new DocumentSnapshot(id));
    }

    public Task<Void> set(Object data) {
        return Tasks.forResult(null);
    }

    public Task<Void> set(Object data, SetOptions options) {
        return Tasks.forResult(null);
    }

    public Task<Void> update(Map<String, Object> data) {
        return Tasks.forResult(null);
    }

    public Task<Void> update(String field, Object value, Object... moreFieldsAndValues) {
        return Tasks.forResult(null);
    }

    public Task<Void> delete() {
        return Tasks.forResult(null);
    }

    public ListenerRegistration addSnapshotListener(EventListener<DocumentSnapshot> listener) {
        return () -> {};
    }

    public ListenerRegistration addSnapshotListener(MetadataChanges metadata, EventListener<DocumentSnapshot> listener) {
        return () -> {};
    }

    public ListenerRegistration addSnapshotListener(Executor executor, EventListener<DocumentSnapshot> listener) {
        return () -> {};
    }

    public ListenerRegistration addSnapshotListener(android.app.Activity activity, EventListener<DocumentSnapshot> listener) {
        return () -> {};
    }

    public ListenerRegistration addSnapshotListener(android.app.Activity activity, MetadataChanges metadata, EventListener<DocumentSnapshot> listener) {
        return () -> {};
    }
}
