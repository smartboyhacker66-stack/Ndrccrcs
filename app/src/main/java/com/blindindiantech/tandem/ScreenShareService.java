package com.blindindiantech.tandem;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;

import androidx.core.content.IntentCompat;

import org.webrtc.VideoTrack;

/**
 * Foreground service on the phone that is being helped. It owns the screen capture and the
 * WebRTC session, and shows a persistent notification with a Disconnect action.
 */
public class ScreenShareService extends Service implements Session.Hook {
    static final String EXTRA_ID = "id";
    static final String EXTRA_DATA = "data";
    static final String ACTION_STOP = "com.blindindiantech.tandem.STOP";
    private static final String CHANNEL = "tandem_session";
    private static final int NOTIFICATION_ID = 1;

    private RtcSession rtc;
    private boolean warnedNoAccessibility;

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
        public void onVideoTrack(VideoTrack track) {
        }

        @Override
        public void onControl(String json) {
            if (!RemoteAccessibilityService.isEnabled()) {
                if (!warnedNoAccessibility) {
                    warnedNoAccessibility = true;
                    Session.setStatus("The accessibility service is off, so remote control is not possible.");
                }
                return;
            }
            RemoteAccessibilityService.dispatch(json);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            if (Session.isActive()) {
                Session.disconnect();
            } else {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (intent == null || rtc != null) {
            return START_NOT_STICKY;
        }

        startInForeground();

        String id = intent.getStringExtra(EXTRA_ID);
        Intent projection = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent.class);
        if (id == null || projection == null) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        Session.begin(this);
        rtc = new RtcSession(getApplicationContext(), true, id, projection, events);
        rtc.start();
        return START_NOT_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW));

        Intent stop = new Intent(this, ScreenShareService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 2, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_launcher),
                        getString(R.string.notif_disconnect), stopPi).build())
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    /** Session.Hook: called when the user disconnects or the connection ends. */
    @Override
    public void disconnect() {
        if (rtc != null) rtc.close();
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        Session.release(this);
        if (rtc != null) rtc.close();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
