package com.google.firebase.firestore;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class WriteBatch {
    private static class BatchOp {
        final int type; // 0 = set, 1 = mergeSet/update, 2 = delete
        final DocumentReference doc;
        final Object data;

        BatchOp(int type, DocumentReference doc, Object data) {
            this.type = type;
            this.doc = doc;
            this.data = data;
        }
    }

    private final List<BatchOp> ops = new ArrayList<>();

    public WriteBatch set(DocumentReference document, Object data) {
        if (document != null) {
            ops.add(new BatchOp(0, document, data));
        }
        return this;
    }

    public WriteBatch set(DocumentReference document, Object data, SetOptions options) {
        if (document != null) {
            ops.add(new BatchOp(1, document, data));
        }
        return this;
    }

    public WriteBatch update(DocumentReference document, Map<String, Object> data) {
        if (document != null) {
            ops.add(new BatchOp(1, document, data));
        }
        return this;
    }

    public WriteBatch update(DocumentReference document, String field, Object value, Object... moreFieldsAndValues) {
        if (document != null) {
            Map<String, Object> map = new HashMap<>();
            if (field != null) map.put(field, value);
            if (moreFieldsAndValues != null) {
                for (int i = 0; i + 1 < moreFieldsAndValues.length; i += 2) {
                    Object k = moreFieldsAndValues[i];
                    Object v = moreFieldsAndValues[i + 1];
                    if (k != null) map.put(String.valueOf(k), v);
                }
            }
            ops.add(new BatchOp(1, document, map));
        }
        return this;
    }

    public WriteBatch delete(DocumentReference document) {
        if (document != null) {
            ops.add(new BatchOp(2, document, null));
        }
        return this;
    }

    public Task<Void> commit() {
        if (ops.isEmpty()) {
            return new Task<>((Void) null);
        }
        final TaskCompletionSource<Void> tcs = new TaskCompletionSource<>();
        final AtomicInteger remaining = new AtomicInteger(ops.size());
        for (BatchOp op : ops) {
            Task<Void> t;
            if (op.type == 0) {
                t = op.doc.set(op.data);
            } else if (op.type == 1) {
                t = op.doc.set(op.data, SetOptions.merge());
            } else {
                t = op.doc.delete();
            }
            t.addOnCompleteListener(res -> {
                if (remaining.decrementAndGet() == 0) {
                    tcs.trySetResult(null);
                }
            });
        }
        return tcs.getTask();
    }
}
