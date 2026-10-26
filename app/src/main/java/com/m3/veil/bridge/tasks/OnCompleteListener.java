package com.m3.veil.bridge.tasks;

import androidx.annotation.NonNull;

public interface OnCompleteListener<TResult> {
    void onComplete(@NonNull Task<TResult> task);
}
