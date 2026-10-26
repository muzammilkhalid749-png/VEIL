package com.m3.veil.bridge.firestore;

import com.m3.veil.bridge.tasks.Task;
import com.m3.veil.bridge.tasks.Tasks;
import java.util.Map;

public class WriteBatch {
    public WriteBatch set(DocumentReference document, Object data) {
        return this;
    }

    public WriteBatch set(DocumentReference document, Object data, SetOptions options) {
        return this;
    }

    public WriteBatch update(DocumentReference document, Map<String, Object> data) {
        return this;
    }

    public WriteBatch update(DocumentReference document, String field, Object value, Object... moreFieldsAndValues) {
        return this;
    }

    public WriteBatch delete(DocumentReference document) {
        return this;
    }

    public Task<Void> commit() {
        return Tasks.forResult(null);
    }
}
