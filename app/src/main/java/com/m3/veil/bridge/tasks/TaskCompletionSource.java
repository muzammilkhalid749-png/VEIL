package com.m3.veil.bridge.tasks;

import androidx.annotation.NonNull;

public class TaskCompletionSource<TResult> {
    private final Task<TResult> task = new Task<>();

    public Task<TResult> getTask() {
        return task;
    }

    public void setResult(TResult result) {
        // Safe no-op or complete
    }

    public void setException(@NonNull Exception e) {
        // Safe no-op
    }
}
