package com.m3.veil.bridge.firestore;

import com.m3.veil.bridge.tasks.Task;
import com.m3.veil.bridge.tasks.Tasks;
import java.util.concurrent.Executor;

public class Query {
    public enum Direction {
        ASCENDING,
        DESCENDING
    }

    public Query whereEqualTo(String field, Object value) {
        return this;
    }

    public Query whereGreaterThan(String field, Object value) {
        return this;
    }

    public Query whereArrayContains(String field, Object value) {
        return this;
    }

    public Query orderBy(String field) {
        return this;
    }

    public Query orderBy(String field, Direction direction) {
        return this;
    }

    public Query limit(long limit) {
        return this;
    }

    public Task<QuerySnapshot> get() {
        return Tasks.forResult(new QuerySnapshot());
    }

    public ListenerRegistration addSnapshotListener(EventListener<QuerySnapshot> listener) {
        return () -> {};
    }

    public ListenerRegistration addSnapshotListener(MetadataChanges metadata, EventListener<QuerySnapshot> listener) {
        return () -> {};
    }

    public ListenerRegistration addSnapshotListener(Executor executor, EventListener<QuerySnapshot> listener) {
        return () -> {};
    }

    public ListenerRegistration addSnapshotListener(android.app.Activity activity, EventListener<QuerySnapshot> listener) {
        return () -> {};
    }

    public ListenerRegistration addSnapshotListener(android.app.Activity activity, MetadataChanges metadata, EventListener<QuerySnapshot> listener) {
        return () -> {};
    }
}
