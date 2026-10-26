package com.m3.veil.bridge.tasks;

import androidx.annotation.NonNull;

public interface OnFailureListener {
    void onFailure(@NonNull Exception e);
}
