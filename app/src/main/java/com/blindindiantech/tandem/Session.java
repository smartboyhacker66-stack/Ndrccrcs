package com.blindindiantech.tandem;

import android.os.Handler;
import android.os.Looper;

/** Tracks the single active connection, its status text, and plays connect/disconnect feedback. */
final class Session {
    interface Hook {
        /** Tear everything down. Must not call back into Session. */
        void disconnect();
    }

    interface StatusListener {
        void onStatus(String text, boolean connected);
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static Hook hook;
    private static boolean connected;
    private static StatusListener listener;
    private static String status = "Not connected";

    private Session() {}

    static synchronized boolean isActive() {
        return hook != null;
    }

    static void begin(Hook h) {
        Hook old;
        synchronized (Session.class) {
            old = hook;
            hook = h;
            connected = false;
        }
        if (old != null && old != h) old.disconnect();
    }

    /** Forget a hook without any sound (used when its owner is destroyed). */
    static synchronized void release(Hook h) {
        if (hook == h) {
            hook = null;
            connected = false;
        }
    }

    static void setListener(StatusListener l) {
        synchronized (Session.class) {
            listener = l;
        }
        push();
    }

    /** Removes the listener only if it is still the given one (activities overlap while switching). */
    static void clearListener(StatusListener l) {
        synchronized (Session.class) {
            if (listener == l) listener = null;
        }
    }

    static void setStatus(String s) {
        synchronized (Session.class) {
            status = s;
        }
        push();
    }

    private static void push() {
        final StatusListener l;
        final String s;
        final boolean c;
        synchronized (Session.class) {
            l = listener;
            s = status;
            c = connected;
        }
        if (l != null) UI.post(() -> l.onStatus(s, c));
    }

    static void markConnected() {
        synchronized (Session.class) {
            if (hook == null || connected) return;
            connected = true;
            status = "Connected successfully";
        }
        Feedback.surge("Connected successfully");
        push();
    }

    /** User pressed Disconnect Connection. */
    static void disconnect() {
        end("Connection disconnected", true);
    }

    /** The connection failed or the other side left. */
    static void fail(String reason) {
        end(reason, false);
    }

    private static void end(String reason, boolean user) {
        Hook h;
        boolean wasConnected;
        synchronized (Session.class) {
            h = hook;
            wasConnected = connected;
            hook = null;
            connected = false;
        }
        if (h == null) {
            if (user) {
                Feedback.speak("There is no active connection.");
                setStatus("Not connected");
            }
            return;
        }
        h.disconnect();
        if (wasConnected || user) {
            Feedback.release("Connection disconnected");
            setStatus(user ? "Connection disconnected" : reason);
        } else {
            Feedback.speak(reason);
            setStatus(reason);
        }
    }
}
