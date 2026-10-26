package com.google.android.gms.tasks;

public class Tasks {
    public static <TResult> Task<TResult> forResult(TResult result) {
        return new Task<>(result);
    }

    public static <TResult> Task<TResult> forException(Exception exception) {
        return new Task<>(exception);
    }

    public static <TResult> TResult await(Task<TResult> task) {
        return task != null ? task.getResult() : null;
    }
}
