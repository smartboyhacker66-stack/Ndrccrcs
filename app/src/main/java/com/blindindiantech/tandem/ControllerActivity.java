package com.blindindiantech.tandem;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

/** The helper's screen: shows the Meeting ID, then the remote screen and the control buttons. */
public class ControllerActivity extends AppCompatActivity
        implements Session.Hook, Session.StatusListener {
    static final String EXTRA_ID = "id";

    private String meetingId;
    private RtcSession rtc;
    private SurfaceViewRenderer remote;
    private VideoTrack track;
    private TextView tvStatus;
    private TextView tvPointer;

    private volatile int videoW;
    private volatile int videoH;
    private float pointerX = 0.5f;
    private float pointerY = 0.5f;
    private float downX;
    private float downY;
    private long downTime;

    private final RtcSession.Events events = new RtcSession.Events() {
        @Override
        public void onStatus(String status) {
            Session.setStatus(status);
        }

        @Override
        public void onConnected() {
            Session.markConnected();
        }

        @Override
        public void onClosed(String reason) {
            Session.fail(reason);
        }

        @Override
        public void onVideoTrack(final VideoTrack t) {
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                track = t;
                t.addSink(remote);
                remote.setVisibility(View.VISIBLE);
            });
        }

        @Override
        public void onControl(String json) {
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_controller);
        Feedback.init(this);

        meetingId = getIntent().getStringExtra(EXTRA_ID);
        TextView tvId = findViewById(R.id.tvMeetingId);
        tvId.setText(meetingId);
        tvId.setContentDescription(getString(R.string.meeting_id_label) + ". " + Meeting.spoken(meetingId));
        tvStatus = findViewById(R.id.tvStatus);
        tvPointer = findViewById(R.id.tvPointer);
        remote = findViewById(R.id.remoteView);

        rtc = new RtcSession(getApplicationContext(), false, meetingId, null, events);
        remote.init(rtc.eglContext(), new RendererCommon.RendererEvents() {
            @Override
            public void onFirstFrameRendered() {
            }

            @Override
            public void onFrameResolutionChanged(int w, int h, int rotation) {
                videoW = w;
                videoH = h;
            }
        });
        remote.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        remote.setEnableHardwareScaler(true);
        remote.setOnTouchListener(this::onRemoteTouch);

        bindControls();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                disconnectOrFinish();
            }
        });

        Session.begin(this);
        Session.setListener(this);
        copyId();
        Feedback.speak("Your Meeting ID is " + Meeting.spoken(meetingId)
                + ". It has been copied to the clipboard. Give it to the other person.");
        rtc.start();
    }

    private void bindControls() {
        findViewById(R.id.btnCopyId).setOnClickListener(v -> {
            copyId();
            Feedback.speak("Meeting ID copied.");
        });
        findViewById(R.id.btnRepeatId).setOnClickListener(v ->
                Feedback.speak("Your Meeting ID is " + Meeting.spoken(meetingId)));
        findViewById(R.id.btnDisconnect).setOnClickListener(v -> disconnectOrFinish());

        click(R.id.btnBack, () -> sendGlobal("back"));
        click(R.id.btnHome, () -> sendGlobal("home"));
        click(R.id.btnRecents, () -> sendGlobal("recents"));

        click(R.id.btnSwipeUp, () -> sendSwipe(0.5f, 0.75f, 0.5f, 0.25f, 300));
        click(R.id.btnSwipeDown, () -> sendSwipe(0.5f, 0.25f, 0.5f, 0.75f, 300));
        click(R.id.btnSwipeLeft, () -> sendSwipe(0.8f, 0.5f, 0.2f, 0.5f, 300));
        click(R.id.btnSwipeRight, () -> sendSwipe(0.2f, 0.5f, 0.8f, 0.5f, 300));

        click(R.id.btnPtrUp, () -> movePointer(0f, -0.1f));
        click(R.id.btnPtrDown, () -> movePointer(0f, 0.1f));
        click(R.id.btnPtrLeft, () -> movePointer(-0.1f, 0f));
        click(R.id.btnPtrRight, () -> movePointer(0.1f, 0f));
        click(R.id.btnTapPointer, () -> sendTap(pointerX, pointerY));
    }

    private void click(int id, Runnable r) {
        Button b = findViewById(id);
        b.setOnClickListener(v -> r.run());
    }

    private void copyId() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Meeting ID", meetingId));
    }

    private void disconnectOrFinish() {
        if (Session.isActive()) Session.disconnect();
        else finish();
    }

    // ---- Control messages ---------------------------------------------------------

    private void send(JSONObject o) {
        if (!rtc.sendControl(o.toString())) {
            Feedback.speak("Not connected yet.");
        }
    }

    private void sendGlobal(String key) {
        try {
            send(new JSONObject().put("a", "global").put("k", key));
        } catch (JSONException ignored) {
        }
    }

    private void sendTap(float x, float y) {
        try {
            send(new JSONObject().put("a", "tap").put("x", x).put("y", y));
        } catch (JSONException ignored) {
        }
    }

    private void sendSwipe(float x1, float y1, float x2, float y2, long ms) {
        try {
            send(new JSONObject().put("a", "swipe")
                    .put("x1", x1).put("y1", y1).put("x2", x2).put("y2", y2).put("ms", ms));
        } catch (JSONException ignored) {
        }
    }

    private void movePointer(float dx, float dy) {
        pointerX = Math.max(0f, Math.min(1f, pointerX + dx));
        pointerY = Math.max(0f, Math.min(1f, pointerY + dy));
        tvPointer.setText("Pointer: " + Math.round(pointerX * 100) + " percent across, "
                + Math.round(pointerY * 100) + " percent down");
    }

    // ---- Touch on the remote view -------------------------------------------------

    /** Maps a touch point on the view to 0..1 coordinates of the remote screen, or null. */
    private float[] normalize(float x, float y) {
        int vw = videoW;
        int vh = videoH;
        if (vw <= 0 || vh <= 0 || remote.getWidth() == 0 || remote.getHeight() == 0) return null;
        float scale = Math.min((float) remote.getWidth() / vw, (float) remote.getHeight() / vh);
        float dw = vw * scale;
        float dh = vh * scale;
        float ox = (remote.getWidth() - dw) / 2f;
        float oy = (remote.getHeight() - dh) / 2f;
        float nx = (x - ox) / dw;
        float ny = (y - oy) / dh;
        if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return null;
        return new float[]{nx, ny};
    }

    private boolean onRemoteTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downTime = e.getEventTime();
                return true;
            case MotionEvent.ACTION_UP:
                float[] a = normalize(downX, downY);
                float[] b = normalize(e.getX(), e.getY());
                if (a != null && b != null) {
                    float slop = 24f * getResources().getDisplayMetrics().density;
                    float moved = (float) Math.hypot(e.getX() - downX, e.getY() - downY);
                    if (moved < slop) {
                        sendTap(b[0], b[1]);
                    } else {
                        sendSwipe(a[0], a[1], b[0], b[1], e.getEventTime() - downTime);
                    }
                }
                v.performClick();
                return true;
            default:
                return true;
        }
    }

    // ---- Session.Hook / StatusListener --------------------------------------------

    @Override
    public void disconnect() {
        rtc.close();
        runOnUiThread(this::finish);
    }

    @Override
    public void onStatus(String text, boolean connected) {
        tvStatus.setText(text);
    }

    @Override
    protected void onDestroy() {
        Session.clearListener(this);
        Session.release(this);
        rtc.close();
        if (track != null) {
            try {
                track.removeSink(remote);
            } catch (Exception ignored) {
            }
        }
        remote.release();
        rtc.releaseEgl();
        super.onDestroy();
    }
}
