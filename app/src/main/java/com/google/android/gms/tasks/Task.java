package com.google.android.gms.tasks;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public class Task<TResult> {
    private final Object lock = new Object();
    private final CountDownLatch completionLatch = new CountDownLatch(1);
    private boolean isComplete = false;
    private boolean isSuccessful = false;
    private TResult result = null;
    private Exception exception = null;

    private final List<Runnable> pendingListeners = new ArrayList<>();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Executor MAIN_EXECUTOR = command -> {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            command.run();
        } else {
            MAIN_HANDLER.post(command);
        }
    };

    public Task() {
        this.isComplete = false;
        this.isSuccessful = false;
    }

    public Task(TResult result) {
        this.result = result;
        this.isSuccessful = true;
        this.isComplete = true;
        this.completionLatch.countDown();
    }

    public Task(Exception exception) {
        this.exception = exception;
        this.isSuccessful = false;
        this.isComplete = true;
        this.completionLatch.countDown();
    }

    public void setResultInternal(TResult res) {
        List<Runnable> toRun;
        synchronized (lock) {
            if (isComplete) return;
            this.result = res;
            this.isSuccessful = true;
            this.isComplete = true;
            this.completionLatch.countDown();
            toRun = new ArrayList<>(pendingListeners);
            pendingListeners.clear();
        }
        for (Runnable r : toRun) {
            r.run();
        }
    }

    public void setExceptionInternal(Exception exc) {
        List<Runnable> toRun;
        synchronized (lock) {
            if (isComplete) return;
            this.exception = exc != null ? exc : new Exception("Task failed");
            this.isSuccessful = false;
            this.isComplete = true;
            this.completionLatch.countDown();
            toRun = new ArrayList<>(pendingListeners);
            pendingListeners.clear();
        }
        for (Runnable r : toRun) {
            r.run();
        }
    }

    public boolean awaitInternal(long timeout, TimeUnit unit) throws InterruptedException {
        return completionLatch.await(timeout, unit);
    }

    public boolean isComplete() {
        synchronized (lock) {
            return isComplete;
        }
    }

    public boolean isSuccessful() {
        synchronized (lock) {
            return isComplete && isSuccessful;
        }
    }

    @Nullable
    public TResult getResult() {
        synchronized (lock) {
            return result;
        }
    }

    @Nullable
    public Exception getException() {
        synchronized (lock) {
            return exception;
        }
    }

    @NonNull
    public Task<TResult> addOnCompleteListener(@NonNull OnCompleteListener<TResult> listener) {
        return addOnCompleteListener(MAIN_EXECUTOR, listener);
    }

    @NonNull
    public Task<TResult> addOnCompleteListener(@NonNull Executor executor, @NonNull OnCompleteListener<TResult> listener) {
        if (listener == null) return this;
        Runnable action = () -> executor.execute(() -> {
            try {
                listener.onComplete(Task.this);
            } catch (Exception ignored) {
            }
        });
        boolean runNow;
        synchronized (lock) {
            runNow = isComplete;
            if (!runNow) {
                pendingListeners.add(action);
            }
        }
        if (runNow) {
            action.run();
        }
        return this;
    }

    @NonNull
    public Task<TResult> addOnCompleteListener(@NonNull android.app.Activity activity, @NonNull OnCompleteListener<TResult> listener) {
        return addOnCompleteListener(MAIN_EXECUTOR, listener);
    }

    @NonNull
    public Task<TResult> addOnSuccessListener(@NonNull OnSuccessListener<? super TResult> listener) {
        return addOnSuccessListener(MAIN_EXECUTOR, listener);
    }

    @NonNull
    public Task<TResult> addOnSuccessListener(@NonNull Executor executor, @NonNull OnSuccessListener<? super TResult> listener) {
        if (listener == null) return this;
        Runnable action = () -> {
            if (isSuccessful()) {
                final TResult res = getResult();
                executor.execute(() -> {
                    try {
                        listener.onSuccess(res);
                    } catch (Exception ignored) {
                    }
                });
            }
        };
        boolean runNow;
        synchronized (lock) {
            runNow = isComplete;
            if (!runNow) {
                pendingListeners.add(action);
            }
        }
        if (runNow) {
            action.run();
        }
        return this;
    }

    @NonNull
    public Task<TResult> addOnSuccessListener(@NonNull android.app.Activity activity, @NonNull OnSuccessListener<? super TResult> listener) {
        return addOnSuccessListener(MAIN_EXECUTOR, listener);
    }

    @NonNull
    public Task<TResult> addOnFailureListener(@NonNull OnFailureListener listener) {
        return addOnFailureListener(MAIN_EXECUTOR, listener);
    }

    @NonNull
    public Task<TResult> addOnFailureListener(@NonNull Executor executor, @NonNull OnFailureListener listener) {
        if (listener == null) return this;
        Runnable action = () -> {
            if (isComplete() && !isSuccessful()) {
                final Exception exc = getException() != null ? getException() : new Exception("Task failed");
                executor.execute(() -> {
                    try {
                        listener.onFailure(exc);
                    } catch (Exception ignored) {
                    }
                });
            }
        };
        boolean runNow;
        synchronized (lock) {
            runNow = isComplete;
            if (!runNow) {
                pendingListeners.add(action);
            }
        }
        if (runNow) {
            action.run();
        }
        return this;
    }

    @NonNull
    public Task<TResult> addOnFailureListener(@NonNull android.app.Activity activity, @NonNull OnFailureListener listener) {
        return addOnFailureListener(MAIN_EXECUTOR, listener);
    }
}
