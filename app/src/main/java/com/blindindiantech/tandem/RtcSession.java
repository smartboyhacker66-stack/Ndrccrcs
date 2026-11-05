package com.blindindiantech.tandem;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjection;
import android.util.DisplayMetrics;
import android.view.Display;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One WebRTC session.
 * Sharer    = the phone being controlled: captures its screen and receives control messages.
 * Controller = the helper: receives the video and sends control messages.
 */
final class RtcSession implements Signaling.Listener {
    interface Events {
        void onStatus(String status);

        void onConnected();

        void onClosed(String reason);

        void onVideoTrack(VideoTrack track);

        void onControl(String json);
    }

    private static boolean factoryInitialized;

    private final Context app;
    private final boolean sharer;
    private final String meetingId;
    private final Intent projectionData;
    private final Events events;

    private final EglBase egl = EglBase.create();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final List<IceCandidate> pendingIce = new ArrayList<>();
    private boolean remoteDescriptionSet;
    private boolean connectedFired;

    private Signaling signaling;
    private PeerConnectionFactory factory;
    private PeerConnection pc;
    private volatile DataChannel dataChannel;
    private ScreenCapturerAndroid capturer;
    private SurfaceTextureHelper textureHelper;
    private VideoSource videoSource;
    private VideoTrack localTrack;
    private volatile Thread teardownThread;

    RtcSession(Context context, boolean sharer, String meetingId, Intent projectionData, Events events) {
        this.app = context.getApplicationContext();
        this.sharer = sharer;
        this.meetingId = meetingId;
        this.projectionData = projectionData;
        this.events = events;
    }

    EglBase.Context eglContext() {
        return egl.getEglBaseContext();
    }

    void start() {
        synchronized (RtcSession.class) {
            if (!factoryInitialized) {
                PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions());
                factoryInitialized = true;
            }
        }
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
                .createPeerConnectionFactory();
        events.onStatus("Connecting to the server...");
        signaling = new Signaling(this);
        signaling.connect();
    }

    /** Sends a control message to the sharer. Returns false if the channel is not open. */
    boolean sendControl(String json) {
        DataChannel ch = dataChannel;
        if (ch == null || ch.state() != DataChannel.State.OPEN) return false;
        ch.send(new DataChannel.Buffer(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)), false));
        return true;
    }

    // ---- Signaling.Listener -------------------------------------------------------

    @Override
    public void onOpen() {
        sendSignalingMessage(sharer ? "join" : "create");
    }

    private void sendSignalingMessage(String type) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("id", meetingId);
            signaling.send(o);
        } catch (JSONException ignored) {
        }
    }

    @Override
    public synchronized void onMessage(JSONObject m) {
        if (closed.get()) return;
        String type = m.optString("type");
        switch (type) {
            case "created":
                events.onStatus("Meeting is ready. Waiting for the other device to enter the Meeting ID.");
                break;
            case "joined":
                events.onStatus("Meeting found. Setting up the connection...");
                break;
            case "peer-joined":
                events.onStatus("Other device joined. Connecting...");
                startPeerConnection();
                break;
            case "signal":
                handleSignal(m.optJSONObject("data"));
                break;
            case "peer-left":
                fireClosed("The other device disconnected.");
                break;
            case "error":
                fireClosed(describeError(m.optString("code")));
                break;
            default:
                break;
        }
    }

    @Override
    public void onClosed(String reason) {
        fireClosed(reason);
    }

    private static String describeError(String code) {
        switch (code) {
            case "no-such-meeting":
                return "That Meeting ID was not found. Please check it and try again.";
            case "meeting-busy":
                return "That meeting already has another device connected.";
            case "id-in-use":
                return "That Meeting ID is already in use. Please generate a new one.";
            case "bad-id":
                return "The Meeting ID is not valid.";
            case "rate-limited":
                return "Too many attempts. Please wait a minute and try again.";
            case "meeting-expired":
                return "The meeting expired because nobody joined.";
            default:
                return "The server reported a problem.";
        }
    }

    private void fireClosed(String reason) {
        if (!closed.get()) events.onClosed(reason);
    }

    // ---- WebRTC -------------------------------------------------------------------

    private void startPeerConnection() {
        if (pc != null) return;
        List<PeerConnection.IceServer> servers = Arrays.asList(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
        PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(servers);
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        pc = factory.createPeerConnection(cfg, new PeerObserver());
        if (pc == null) {
            fireClosed("Could not set up the connection.");
            return;
        }
        if (sharer) {
            startScreenCapture();
            DataChannel ch = pc.createDataChannel("control", new DataChannel.Init());
            dataChannel = ch;
            ch.registerObserver(new ChannelObserver(ch));
            createOffer();
        }
    }

    private void startScreenCapture() {
        DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
        Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        float scale = Math.min(1f, 1280f / Math.max(metrics.widthPixels, metrics.heightPixels));
        int w = Math.round(metrics.widthPixels * scale) & ~1;
        int h = Math.round(metrics.heightPixels * scale) & ~1;

        capturer = new ScreenCapturerAndroid(projectionData, new MediaProjection.Callback() {
            @Override
            public void onStop() {
                fireClosed("Screen sharing was stopped.");
            }
        });
        textureHelper = SurfaceTextureHelper.create("screen-capture", egl.getEglBaseContext());
        videoSource = factory.createVideoSource(true);
        capturer.initialize(textureHelper, app, videoSource.getCapturerObserver());
        capturer.startCapture(w, h, 15);
        localTrack = factory.createVideoTrack("screen", videoSource);
        pc.addTrack(localTrack, Collections.singletonList("tandem-stream"));
    }

    private void createOffer() {
        pc.createOffer(new SdpAdapter() {
            @Override
            public void onCreateSuccess(SessionDescription d) {
                pc.setLocalDescription(new SdpAdapter(), d);
                sendSdp(d);
            }

            @Override
            public void onCreateFailure(String error) {
                fireClosed("Could not set up the connection.");
            }
        }, new MediaConstraints());
    }

    private void createAnswer() {
        pc.createAnswer(new SdpAdapter() {
            @Override
            public void onCreateSuccess(SessionDescription d) {
                pc.setLocalDescription(new SdpAdapter(), d);
                sendSdp(d);
            }

            @Override
            public void onCreateFailure(String error) {
                fireClosed("Could not set up the connection.");
            }
        }, new MediaConstraints());
    }

    private void sendSdp(SessionDescription d) {
        try {
            JSONObject sdp = new JSONObject();
            sdp.put("type", d.type.canonicalForm());
            sdp.put("sdp", d.description);
            sendSignal(new JSONObject().put("sdp", sdp));
        } catch (JSONException ignored) {
        }
    }

    private void sendIce(IceCandidate c) {
        try {
            JSONObject ice = new JSONObject();
            ice.put("sdpMid", c.sdpMid);
            ice.put("sdpMLineIndex", c.sdpMLineIndex);
            ice.put("candidate", c.sdp);
            sendSignal(new JSONObject().put("ice", ice));
        } catch (JSONException ignored) {
        }
    }

    private void sendSignal(JSONObject data) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "signal");
            o.put("data", data);
            signaling.send(o);
        } catch (JSONException ignored) {
        }
    }

    private void handleSignal(JSONObject data) {
        if (data == null || pc == null) return;
        JSONObject sdp = data.optJSONObject("sdp");
        JSONObject ice = data.optJSONObject("ice");
        if (sdp != null) {
            final SessionDescription.Type t =
                    SessionDescription.Type.fromCanonicalForm(sdp.optString("type"));
            SessionDescription desc = new SessionDescription(t, sdp.optString("sdp"));
            pc.setRemoteDescription(new SdpAdapter() {
                @Override
                public void onSetSuccess() {
                    synchronized (RtcSession.this) {
                        remoteDescriptionSet = true;
                        for (IceCandidate c : pendingIce) pc.addIceCandidate(c);
                        pendingIce.clear();
                    }
                    if (t == SessionDescription.Type.OFFER && !sharer) createAnswer();
                }

                @Override
                public void onSetFailure(String error) {
                    fireClosed("Could not set up the connection.");
                }
            }, desc);
        } else if (ice != null) {
            IceCandidate c = new IceCandidate(
                    ice.optString("sdpMid"), ice.optInt("sdpMLineIndex"), ice.optString("candidate"));
            if (remoteDescriptionSet) pc.addIceCandidate(c);
            else pendingIce.add(c);
        }
    }

    // ---- Observers ----------------------------------------------------------------

    private static class SdpAdapter implements SdpObserver {
        @Override
        public void onCreateSuccess(SessionDescription d) {}

        @Override
        public void onSetSuccess() {}

        @Override
        public void onCreateFailure(String error) {}

        @Override
        public void onSetFailure(String error) {}
    }

    private final class ChannelObserver implements DataChannel.Observer {
        private final DataChannel channel;

        ChannelObserver(DataChannel channel) {
            this.channel = channel;
        }

        @Override
        public void onBufferedAmountChange(long previousAmount) {}

        @Override
        public void onStateChange() {}

        @Override
        public void onMessage(DataChannel.Buffer buffer) {
            if (buffer.binary) return;
            ByteBuffer data = buffer.data;
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            events.onControl(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    private final class PeerObserver implements PeerConnection.Observer {
        @Override
        public void onSignalingChange(PeerConnection.SignalingState state) {}

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            if (state == PeerConnection.IceConnectionState.CONNECTED
                    || state == PeerConnection.IceConnectionState.COMPLETED) {
                boolean fire;
                synchronized (RtcSession.this) {
                    fire = !connectedFired;
                    connectedFired = true;
                }
                if (fire && !closed.get()) events.onConnected();
            } else if (state == PeerConnection.IceConnectionState.FAILED) {
                fireClosed("The connection failed. A network that blocks direct connections may be the cause.");
            }
        }

        @Override
        public void onIceConnectionReceivingChange(boolean receiving) {}

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}

        @Override
        public void onIceCandidate(IceCandidate candidate) {
            sendIce(candidate);
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] candidates) {}

        @Override
        public void onAddStream(MediaStream stream) {}

        @Override
        public void onRemoveStream(MediaStream stream) {}

        @Override
        public void onDataChannel(DataChannel channel) {
            dataChannel = channel;
            channel.registerObserver(new ChannelObserver(channel));
        }

        @Override
        public void onRenegotiationNeeded() {}

        @Override
        public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {}

        @Override
        public void onTrack(RtpTransceiver transceiver) {
            MediaStreamTrack track = transceiver.getReceiver().track();
            if (track instanceof VideoTrack) {
                events.onVideoTrack((VideoTrack) track);
            }
        }
    }

    // ---- Shutdown -----------------------------------------------------------------

    /** Idempotent. Stops capture, closes the peer connection and the signaling socket. */
    void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            if (signaling != null) signaling.send(new JSONObject().put("type", "leave"));
        } catch (JSONException ignored) {
        }
        Thread t = new Thread(() -> {
            try {
                if (signaling != null) signaling.close();
                if (dataChannel != null) {
                    dataChannel.close();
                    dataChannel.dispose();
                }
                if (capturer != null) {
                    try {
                        capturer.stopCapture();
                    } catch (Exception ignored) {
                    }
                    capturer.dispose();
                }
                if (pc != null) {
                    pc.close();
                    pc.dispose();
                }
                if (textureHelper != null) textureHelper.dispose();
                if (localTrack != null) localTrack.dispose();
                if (videoSource != null) videoSource.dispose();
                if (factory != null) factory.dispose();
            } catch (Exception ignored) {
            }
            if (sharer) releaseEglNow();
        }, "rtc-teardown");
        teardownThread = t;
        t.start();
    }

    /** For the controller: call after the video renderer has been released. */
    void releaseEgl() {
        final Thread t = teardownThread;
        new Thread(() -> {
            if (t != null) {
                try {
                    t.join(5000);
                } catch (InterruptedException ignored) {
                }
            }
            releaseEglNow();
        }, "rtc-egl-release").start();
    }

    private void releaseEglNow() {
        try {
            egl.release();
        } catch (Exception ignored) {
        }
    }
}
