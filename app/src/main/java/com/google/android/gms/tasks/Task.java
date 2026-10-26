package com.google.android.gms.tasks;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class Task<TResult> {
    private boolean isComplete = true;
    private boolean isSuccessful = true;
    private TResult result = null;
    private Exception exception = null;

    public Task() {}

    public Task(TResult result) {
        this.result = result;
        this.isSuccessful = true;
        this.isComplete = true;
    }

    public Task(Exception exception) {
        this.exception = exception;
        this.isSuccessful = false;
        this.isComplete = true;
    }

    public boolean isComplete() {
        return isComplete;
    }

    public boolean isSuccessful() {
        return isSuccessful;
    }

    @Nullable
    public TResult getResult() {
        return result;
    }

    @Nullable
    public Exception getException() {
        return exception;
    }

    @NonNull
    public Task<TResult> addOnCompleteListener(@NonNull OnCompleteListener<TResult> listener) {
        if (listener != null) {
            listener.onComplete(this);
        }
        return this;
    }

    @NonNull
    public Task<TResult> addOnCompleteListener(@NonNull java.util.concurrent.Executor executor, @NonNull OnCompleteListener<TResult> listener) {
        return addOnCompleteListener(listener);
    }

    @NonNull
    public Task<TResult> addOnCompleteListener(@NonNull android.app.Activity activity, @NonNull OnCompleteListener<TResult> listener) {
        return addOnCompleteListener(listener);
    }

    @NonNull
    public Task<TResult> addOnSuccessListener(@NonNull OnSuccessListener<? super TResult> listener) {
        if (isSuccessful && listener != null) {
            listener.onSuccess(result);
        }
        return this;
    }

    @NonNull
    public Task<TResult> addOnSuccessListener(@NonNull java.util.concurrent.Executor executor, @NonNull OnSuccessListener<? super TResult> listener) {
        return addOnSuccessListener(listener);
    }

    @NonNull
    public Task<TResult> addOnSuccessListener(@NonNull android.app.Activity activity, @NonNull OnSuccessListener<? super TResult> listener) {
        return addOnSuccessListener(listener);
    }

    @NonNull
    public Task<TResult> addOnFailureListener(@NonNull OnFailureListener listener) {
        if (!isSuccessful && listener != null) {
            listener.onFailure(exception != null ? exception : new Exception("Task failed"));
        }
        return this;
    }

    @NonNull
    public Task<TResult> addOnFailureListener(@NonNull java.util.concurrent.Executor executor, @NonNull OnFailureListener listener) {
        return addOnFailureListener(listener);
    }

    @NonNull
    public Task<TResult> addOnFailureListener(@NonNull android.app.Activity activity, @NonNull OnFailureListener listener) {
        return addOnFailureListener(listener);
    }
}
