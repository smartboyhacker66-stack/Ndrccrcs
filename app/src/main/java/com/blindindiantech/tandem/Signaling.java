package com.blindindiantech.tandem;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Thin WebSocket client for the Node.js signaling server. */
final class Signaling {
    interface Listener {
        void onOpen();

        void onMessage(JSONObject message);

        void onClosed(String reason);
    }

    private final OkHttpClient client = new OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();
    private final Listener listener;
    private WebSocket socket;
    private volatile boolean closed;

    Signaling(Listener listener) {
        this.listener = listener;
    }

    void connect() {
        Request request = new Request.Builder().url(ServerWaker.wsUrl()).build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                listener.onOpen();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    listener.onMessage(new JSONObject(text));
                } catch (JSONException ignored) {
                    // Ignore malformed messages.
                }
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(1000, null);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                if (!closed) listener.onClosed("The connection to the server was closed.");
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                if (!closed) listener.onClosed("Could not reach the server.");
            }
        });
    }

    void send(JSONObject message) {
        WebSocket s = socket;
        if (s != null && !closed) s.send(message.toString());
    }

    void close() {
        closed = true;
        WebSocket s = socket;
        if (s != null) {
            try {
                s.close(1000, "bye");
            } catch (Exception ignored) {
            }
        }
        client.dispatcher().executorService().shutdown();
    }
}
