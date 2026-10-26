package com.google.android.gms.tasks;

import java.util.concurrent.TimeUnit;

public class Tasks {
    public static <TResult> Task<TResult> forResult(TResult result) {
        return new Task<>(result);
    }

    public static <TResult> Task<TResult> forException(Exception exception) {
        return new Task<>(exception);
    }

    public static <TResult> TResult await(Task<TResult> task) throws Exception {
        return await(task, 10, TimeUnit.SECONDS);
    }

    public static <TResult> TResult await(Task<TResult> task, long timeout, TimeUnit unit) throws Exception {
        if (task == null) return null;
        if (!task.isComplete()) {
            task.awaitInternal(timeout, unit);
        }
        if (task.isSuccessful()) {
            return task.getResult();
        }
        if (task.getException() != null) {
            throw task.getException();
        }
        return task.getResult();
    }
}
