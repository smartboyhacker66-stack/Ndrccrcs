package com.blindindiantech.tandem;

import java.net.HttpURLConnection;
import java.net.URL;

/** Pings the Render server until it answers HTTP 200 (free tier sleeps when idle). */
final class ServerWaker {
    interface Callback {
        void onReady();
    }

    private static volatile boolean ready;
    private static volatile boolean running;
    private static volatile Callback latest;

    private ServerWaker() {}

    static boolean isReady() {
        return ready;
    }

    static String wsUrl() {
        return "wss://" + BuildConfig.SERVER_HOST;
    }

    /** Starts pinging. If already running, only the callback to notify is replaced. */
    static synchronized void start(final Callback callback) {
        latest = callback;
        if (ready) {
            callback.onReady();
            return;
        }
        if (running) return;
        running = true;
        Thread t = new Thread(() -> {
            while (!ready) {
                if (ping()) {
                    ready = true;
                    break;
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            running = false;
            Callback c = latest;
            if (ready && c != null) c.onReady();
        }, "server-waker");
        t.setDaemon(true);
        t.start();
    }

    private static boolean ping() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://" + BuildConfig.SERVER_HOST + "/health").openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestMethod("GET");
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
