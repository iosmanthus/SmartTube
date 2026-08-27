package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import android.util.Log;

import com.liskovsoft.mediaserviceinterfaces.diagnostics.ApiDiagnostics;

import com.liskovsoft.smartyoutubetv2.common.diagnostics.DiagnosticsConfig.Level;
import com.liskovsoft.smartyoutubetv2.common.net.PinnedHttps;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

/**
 * Pushes structured playback events to a collector, as JSON lines.
 *
 * One JSON object per line, batched and sent over the same kind of pinned https
 * connection the cookie client uses -- with a different certificate and a
 * different token, so the two cannot be confused for each other.
 *
 * Structured rather than raw logcat because raw logcat is what this replaced:
 * shipping the whole stream meant a megabyte of vendor noise per minute, and
 * every question ("which client? did it carry a token? how many 403s?") turned
 * into grep archaeology.
 *
 * Reporting never blocks the caller and never throws into it. A collector that
 * is down must not be able to stall playback, so events queue in memory, the
 * queue is bounded, and a full queue drops its oldest entries.
 */
public final class DiagnosticsReporter {
    private static final String TAG = "Diagnostics";

    private static final Deque<String> QUEUE = new ArrayDeque<>();
    private static volatile boolean sStarted;

    private DiagnosticsReporter() {
    }

    /**
     * Start listening to the api layer.
     *
     * It cannot depend on this module, so it reports through an interface in
     * the one they both share. Attaching is what turns those calls on; until
     * this runs, the api layer reports into nowhere.
     */
    public static void attachToApi() {
        if (!DiagnosticsConfig.isEnabled()) {
            return;
        }

        ApiDiagnostics.setSink(new ApiDiagnostics.Sink() {
            @Override
            public void onApiEvent(String event, Object... keyValues) {
                report(Level.BASIC, event, keyValues);
            }
        });
    }

    /**
     * Record an event.
     *
     * @param minLevel the level at which this event becomes interesting; below
     *                 it the call is a no-op, so callers do not have to guard.
     */
    public static void report(Level minLevel, String event, Object... keyValues) {
        if (!DiagnosticsConfig.atLeast(minLevel)) {
            return;
        }

        try {
            JSONObject obj = new JSONObject();
            obj.put("ts", System.currentTimeMillis());
            obj.put("level", minLevel.name().toLowerCase());
            obj.put("event", event);
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                obj.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
            enqueue(obj.toString());
        } catch (JSONException e) {
            // A malformed event is not worth interrupting playback over.
            Log.w(TAG, "cannot encode event " + event + ": " + e);
        }
    }

    /**
     * Record the size of a secret without recording the secret.
     *
     * Session cookies are the one thing that must never leave here in full, at
     * any level, so there is exactly one way to mention them and it takes a
     * length rather than a value.
     */
    public static void reportSecretLength(String event, String name, String secret) {
        report(Level.BASIC, event, name + "_len", secret == null ? 0 : secret.length());
    }

    private static void enqueue(String line) {
        synchronized (QUEUE) {
            while (QUEUE.size() >= DiagnosticsConfig.QUEUE_LIMIT) {
                // Oldest first: when something is going wrong, the recent
                // events are the ones that explain it.
                QUEUE.removeFirst();
            }
            QUEUE.addLast(line);
        }
        startIfNeeded();
    }

    private static void startIfNeeded() {
        if (sStarted) {
            return;
        }
        synchronized (DiagnosticsReporter.class) {
            if (sStarted) {
                return;
            }
            sStarted = true;
        }

        Thread sender = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(DiagnosticsConfig.FLUSH_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    flush();
                }
            }
        }, "diagnostics-send");
        sender.setDaemon(true);
        sender.start();
    }

    private static void flush() {
        List<String> batch;
        synchronized (QUEUE) {
            if (QUEUE.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(QUEUE);
            QUEUE.clear();
        }

        StringBuilder body = new StringBuilder();
        for (String line : batch) {
            body.append(line).append('\n');
        }

        if (!post(body.toString())) {
            // Put them back at the front so ordering survives an outage, but
            // let the size cap trim if the collector stays down.
            synchronized (QUEUE) {
                for (int i = batch.size() - 1; i >= 0; i--) {
                    if (QUEUE.size() >= DiagnosticsConfig.QUEUE_LIMIT) {
                        break;
                    }
                    QUEUE.addFirst(batch.get(i));
                }
            }
        }
    }

    private static boolean post(String body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(DiagnosticsConfig.endpoint()).openConnection();
            if (!(conn instanceof HttpsURLConnection)) {
                Log.w(TAG, "endpoint is not https; refusing");
                return false;
            }
            PinnedHttps.apply((HttpsURLConnection) conn, DiagnosticsConfig.pin());

            conn.setConnectTimeout(DiagnosticsConfig.CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(DiagnosticsConfig.READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + DiagnosticsConfig.token());
            conn.setRequestProperty("Content-Type", "application/x-ndjson");

            OutputStream out = conn.getOutputStream();
            out.write(body.getBytes("UTF-8"));
            out.flush();
            out.close();

            return conn.getResponseCode() / 100 == 2;
        } catch (Throwable e) {
            Log.w(TAG, "send failed: " + e);
            return false;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                    // disconnecting a dead connection is not worth reporting
                }
            }
        }
    }
}
