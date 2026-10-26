package com.google.android.gms.tasks;

import androidx.annotation.NonNull;

public class TaskCompletionSource<TResult> {
    private final Task<TResult> task = new Task<>();

    public Task<TResult> getTask() {
        return task;
    }

    public void setResult(TResult result) {
        task.setResultInternal(result);
    }

    public void setException(@NonNull Exception e) {
        task.setExceptionInternal(e);
    }

    public boolean trySetResult(TResult result) {
        if (task.isComplete()) return false;
        task.setResultInternal(result);
        return true;
    }

    public boolean trySetException(@NonNull Exception e) {
        if (task.isComplete()) return false;
        task.setExceptionInternal(e);
        return true;
    }
}
