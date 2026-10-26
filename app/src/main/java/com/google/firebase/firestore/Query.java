package com.google.firebase.firestore;

import com.google.android.gms.tasks.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class Query {
    public enum Direction {
        ASCENDING,
        DESCENDING
    }

    public static class FilterSpec {
        public final String field;
        public final String op;
        public final Object value;

        public FilterSpec(String field, String op, Object value) {
            this.field = field;
            this.op = op;
            this.value = value;
        }
    }

    protected final String collectionPath;
    protected final List<FilterSpec> filters;
    protected String orderByField;
    protected Direction orderDirection = Direction.ASCENDING;
    protected long limitCount = 0;

    public Query() {
        this.collectionPath = "";
        this.filters = new ArrayList<>();
    }

    public Query(String collectionPath) {
        this.collectionPath = FirebaseFirestore.normalizePath(collectionPath);
        this.filters = new ArrayList<>();
    }

    protected Query(String collectionPath, List<FilterSpec> filters, String orderByField, Direction orderDirection, long limitCount) {
        this.collectionPath = FirebaseFirestore.normalizePath(collectionPath);
        this.filters = new ArrayList<>(filters);
        this.orderByField = orderByField;
        this.orderDirection = orderDirection;
        this.limitCount = limitCount;
    }

    public Query whereEqualTo(String field, Object value) {
        List<FilterSpec> next = new ArrayList<>(this.filters);
        next.add(new FilterSpec(field, "EQUAL", value));
        return new Query(this.collectionPath, next, this.orderByField, this.orderDirection, this.limitCount);
    }

    public Query whereGreaterThan(String field, Object value) {
        List<FilterSpec> next = new ArrayList<>(this.filters);
        next.add(new FilterSpec(field, "GREATER_THAN", value));
        return new Query(this.collectionPath, next, this.orderByField, this.orderDirection, this.limitCount);
    }

    public Query whereArrayContains(String field, Object value) {
        List<FilterSpec> next = new ArrayList<>(this.filters);
        next.add(new FilterSpec(field, "ARRAY_CONTAINS", value));
        return new Query(this.collectionPath, next, this.orderByField, this.orderDirection, this.limitCount);
    }

    public Query orderBy(String field) {
        return orderBy(field, Direction.ASCENDING);
    }

    public Query orderBy(String field, Direction direction) {
        return new Query(this.collectionPath, this.filters, field, direction != null ? direction : Direction.ASCENDING, this.limitCount);
    }

    public Query limit(long limit) {
        return new Query(this.collectionPath, this.filters, this.orderByField, this.orderDirection, limit);
    }

    public Task<QuerySnapshot> get() {
        return FirebaseFirestore.getInstance().queryCollectionAsync(
                collectionPath, filters, orderByField, orderDirection, limitCount);
    }

    public ListenerRegistration addSnapshotListener(EventListener<QuerySnapshot> listener) {
        return FirebaseFirestore.getInstance().registerQueryListener(
                collectionPath, filters, orderByField, orderDirection, limitCount, listener);
    }

    public ListenerRegistration addSnapshotListener(MetadataChanges metadata, EventListener<QuerySnapshot> listener) {
        return addSnapshotListener(listener);
    }

    public ListenerRegistration addSnapshotListener(Executor executor, EventListener<QuerySnapshot> listener) {
        return addSnapshotListener(listener);
    }

    public ListenerRegistration addSnapshotListener(android.app.Activity activity, EventListener<QuerySnapshot> listener) {
        return addSnapshotListener(listener);
    }

    public ListenerRegistration addSnapshotListener(android.app.Activity activity, MetadataChanges metadata, EventListener<QuerySnapshot> listener) {
        return addSnapshotListener(listener);
    }
}
