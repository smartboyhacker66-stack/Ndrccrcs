package com.blindindiantech.tandem;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Performs the taps, swipes and Back / Home / Recents presses sent by the connected helper.
 * It only dispatches gestures; it does not read any screen content.
 */
public class RemoteAccessibilityService extends AccessibilityService {
    private static volatile RemoteAccessibilityService instance;
    private final Handler main = new Handler(Looper.getMainLooper());

    static boolean isEnabled() {
        return instance != null;
    }

    /** Called from the WebRTC data channel thread. */
    static void dispatch(final String json) {
        final RemoteAccessibilityService s = instance;
        if (s == null) return;
        s.main.post(() -> s.run(json));
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Nothing to do: this service only performs gestures.
    }

    @Override
    public void onInterrupt() {
    }

    private void run(String json) {
        try {
            JSONObject o = new JSONObject(json);
            switch (o.optString("a")) {
                case "tap":
                    tap(unit(o.getDouble("x")), unit(o.getDouble("y")));
                    break;
                case "swipe":
                    swipe(unit(o.getDouble("x1")), unit(o.getDouble("y1")),
                            unit(o.getDouble("x2")), unit(o.getDouble("y2")), o.optLong("ms", 300));
                    break;
                case "global":
                    global(o.optString("k"));
                    break;
                default:
                    break;
            }
        } catch (JSONException ignored) {
        }
    }

    private static float unit(double v) {
        return (float) Math.max(0.0, Math.min(1.0, v));
    }

    private DisplayMetrics screen() {
        DisplayMetrics m = new DisplayMetrics();
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        d.getRealMetrics(m);
        return m;
    }

    private void tap(float nx, float ny) {
        DisplayMetrics m = screen();
        Path p = new Path();
        p.moveTo(nx * (m.widthPixels - 1), ny * (m.heightPixels - 1));
        gesture(p, 60);
    }

    private void swipe(float nx1, float ny1, float nx2, float ny2, long ms) {
        DisplayMetrics m = screen();
        Path p = new Path();
        p.moveTo(nx1 * (m.widthPixels - 1), ny1 * (m.heightPixels - 1));
        p.lineTo(nx2 * (m.widthPixels - 1), ny2 * (m.heightPixels - 1));
        gesture(p, Math.max(80, Math.min(2000, ms)));
    }

    private void gesture(Path path, long durationMs) {
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs))
                .build();
        dispatchGesture(g, null, null);
    }

    private void global(String key) {
        switch (key) {
            case "back":
                performGlobalAction(GLOBAL_ACTION_BACK);
                break;
            case "home":
                performGlobalAction(GLOBAL_ACTION_HOME);
                break;
            case "recents":
                performGlobalAction(GLOBAL_ACTION_RECENTS);
                break;
            default:
                break;
        }
    }
}
