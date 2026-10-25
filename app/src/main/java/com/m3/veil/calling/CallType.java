package com.m3.veil.calling;

/** Call media type. */
public enum CallType {
    AUDIO("audio"),
    VIDEO("video");

    public final String wire;

    CallType(String wire) {
        this.wire = wire;
    }

    public static CallType fromWire(String value) {
        return VIDEO.wire.equalsIgnoreCase(value) ? VIDEO : AUDIO;
    }
}
