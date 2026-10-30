package com.m3.veil.calling;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Centralized, thread-safe Call State Machine for Phase 2 VoIP / WebRTC lifecycle management.
 *
 * <p>Enforces strict state transitions across:
 * <ul>
 *   <li>{@link State#IDLE}</li>
 *   <li>{@link State#OUTGOING_RINGING}</li>
 *   <li>{@link State#INCOMING_RINGING}</li>
 *   <li>{@link State#CONNECTING}</li>
 *   <li>{@link State#CONNECTED}</li>
 *   <li>{@link State#DISCONNECTED}</li>
 *   <li>{@link State#ENDED}</li>
 * </ul>
 * All transitions are synchronized, idempotent, and guarded against race conditions.
 * Includes a 45-second auto-timeout watchdog for unanswered ringing calls.
 */
public final class CallStateMachine {

    private static final String TAG = "CallStateMachine";
    public static final long RINGING_TIMEOUT_MS = 45_000L;

    public enum State {
        IDLE,
        OUTGOING_RINGING,
        INCOMING_RINGING,
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        ENDED
    }

    public interface Listener {
        void onStateTransition(State previousState, State newState, String callId);
        default void onRingingTimeout(State timedOutState, String callId) { }
    }

    private static volatile CallStateMachine sInstance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private State currentState = State.IDLE;
    private String activeCallId = null;
    private Runnable timeoutRunnable;

    private CallStateMachine() {
    }

    public static CallStateMachine getInstance() {
        if (sInstance == null) {
            synchronized (CallStateMachine.class) {
                if (sInstance == null) {
                    sInstance = new CallStateMachine();
                }
            }
        }
        return sInstance;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public synchronized State getState() {
        return currentState;
    }

    public synchronized String getActiveCallId() {
        return activeCallId;
    }

    /**
     * Maps legacy {@link CallState} to strict {@link State}.
     */
    public static State fromCallState(CallState callState) {
        if (callState == null) return State.IDLE;
        switch (callState) {
            case CALLING:
            case PRE_ACCEPT_RECEIVED:
                return State.OUTGOING_RINGING;
            case RECEIVED_CALL:
                return State.INCOMING_RINGING;
            case ACCEPT_SENT:
            case ACCEPT_RECEIVED:
                return State.CONNECTING;
            case CONNECTED_LONELY:
            case ACTIVE:
                return State.CONNECTED;
            case REJOINING:
                return State.DISCONNECTED;
            case ENDING:
                return State.ENDED;
            case NONE:
            default:
                return State.IDLE;
        }
    }

    /**
     * Performs an idempotent, race-guarded state transition.
     *
     * @return true if the state changed, false if it was a no-op or rejected transition.
     */
    public synchronized boolean transitionTo(State targetState, String callId) {
        if (targetState == null) return false;

        // Idempotency guard: same state for same call is a no-op
        if (this.currentState == targetState && (callId == null || callId.equals(this.activeCallId))) {
            return false;
        }

        // Prevent resurrection of an ended call into CONNECTING/CONNECTED unless starting a brand new call
        if (this.currentState == State.ENDED && targetState != State.IDLE
                && targetState != State.OUTGOING_RINGING && targetState != State.INCOMING_RINGING) {
            if (callId != null && callId.equals(this.activeCallId)) {
                Log.w(TAG, "Blocked invalid transition from ENDED to " + targetState + " for call " + callId);
                return false;
            }
        }

        // Prevent transitioning to RINGING if already CONNECTING or CONNECTED on the same callId
        if ((this.currentState == State.CONNECTING || this.currentState == State.CONNECTED)
                && (targetState == State.INCOMING_RINGING || targetState == State.OUTGOING_RINGING)
                && callId != null && callId.equals(this.activeCallId)) {
            Log.w(TAG, "Ignored stale ringing state transition while in " + this.currentState + " for call " + callId);
            return false;
        }

        final State prev = this.currentState;
        this.currentState = targetState;
        if (targetState == State.IDLE) {
            this.activeCallId = null;
        } else if (callId != null && !callId.isEmpty()) {
            this.activeCallId = callId;
        }

        final String resolvedCallId = this.activeCallId;
        Log.i(TAG, "CallStateMachine transition: " + prev + " -> " + targetState + " (callId=" + resolvedCallId + ")");

        // Manage 45-second auto-timeout for unanswered ringing states
        cancelRingingTimeoutInternal();
        if (targetState == State.OUTGOING_RINGING || targetState == State.INCOMING_RINGING) {
            scheduleRingingTimeoutInternal(targetState, resolvedCallId);
        }

        mainHandler.post(() -> {
            for (Listener l : listeners) {
                try {
                    l.onStateTransition(prev, targetState, resolvedCallId);
                } catch (Throwable t) {
                    Log.w(TAG, "Listener error on state transition: " + t.getMessage());
                }
            }
        });
        return true;
    }

    public synchronized void resetToIdle() {
        transitionTo(State.IDLE, null);
    }

    private void scheduleRingingTimeoutInternal(final State ringingState, final String callIdAtSchedule) {
        timeoutRunnable = () -> {
            boolean shouldFire = false;
            synchronized (CallStateMachine.this) {
                if (currentState == ringingState
                        && (callIdAtSchedule == null || callIdAtSchedule.equals(activeCallId))) {
                    shouldFire = true;
                }
            }
            if (shouldFire) {
                Log.w(TAG, "45-second ringing timeout reached in state " + ringingState + " for call " + callIdAtSchedule);
                for (Listener l : listeners) {
                    try {
                        l.onRingingTimeout(ringingState, callIdAtSchedule);
                    } catch (Throwable ignored) {
                    }
                }
            }
        };
        mainHandler.postDelayed(timeoutRunnable, RINGING_TIMEOUT_MS);
    }

    private void cancelRingingTimeoutInternal() {
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }
}
