package com.google.firebase.firestore;

import com.google.firebase.Timestamp;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class DocumentSnapshot {
    private final String id;
    private final Map<String, Object> data;

    public DocumentSnapshot(String id) {
        this.id = id;
        this.data = new HashMap<>();
    }

    public DocumentSnapshot(String id, Map<String, Object> data) {
        this.id = id;
        this.data = data != null ? data : new HashMap<>();
    }

    public boolean exists() {
        return false;
    }

    public String getId() {
        return id;
    }

    public DocumentReference getReference() {
        return new DocumentReference(id != null ? id : "doc");
    }

    public boolean contains(String field) {
        return data != null && data.containsKey(field);
    }

    public Map<String, Object> getData() {
        return Collections.unmodifiableMap(data);
    }

    public Object get(String field) {
        return data.get(field);
    }

    public String getString(String field) {
        Object val = data.get(field);
        return val != null ? String.valueOf(val) : null;
    }

    public Long getLong(String field) {
        Object val = data.get(field);
        if (val instanceof Number) return ((Number) val).longValue();
        return null;
    }

    public Double getDouble(String field) {
        Object val = data.get(field);
        if (val instanceof Number) return ((Number) val).doubleValue();
        return null;
    }

    public Boolean getBoolean(String field) {
        Object val = data.get(field);
        if (val instanceof Boolean) return (Boolean) val;
        return null;
    }

    public Timestamp getTimestamp(String field) {
        Object val = data.get(field);
        if (val instanceof Timestamp) return (Timestamp) val;
        return null;
    }

    public Date getDate(String field) {
        Object val = data.get(field);
        if (val instanceof Date) return (Date) val;
        return null;
    }

    public <T> T toObject(Class<T> valueType) {
        return null;
    }
}
