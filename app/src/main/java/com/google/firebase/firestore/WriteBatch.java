package com.google.firebase.firestore;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
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
