package com.liskovsoft.smartyoutubetv2.common.cookieauth;

import android.util.Log;

import com.liskovsoft.smartyoutubetv2.common.net.PinnedHttps;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

/**
 * Fetches the current Cookie header from the cookie service.
 *
 * The service reads its browser live on every request, so the answer is always
 * current. That matters: a cached snapshot goes stale as Google rotates the
 * session, which is exactly how the earlier file-based approach failed.
 */
public final class CookieAuthClient {
    private static final String TAG = "CookieAuth";
    private static final int MAX_BODY = 128 * 1024;

    /**
     * What a fetch produced: the header, or why there is none.
     *
     * The reason matters more than it looks. "no cookies" from a device with no
     * console is otherwise indistinguishable between a service that is down, a
     * certificate that no longer matches, a token that was rotated, and a
     * browser that got signed out -- four different things to go and fix.
     */
    public static final class Result {
        public final String header;
        public final String reason;

        private Result(String header, String reason) {
            this.header = header;
            this.reason = reason;
        }
    }

    /** The Cookie header, or null. Blocking; never call this on the main thread. */
    public String fetch() {
        return fetchWithReason().header;
    }

    /** As {@link #fetch()}, but says why when it comes back empty. */
    public Result fetchWithReason() {
        if (!CookieAuthConfig.isConfigured()) {
            return new Result(null, "not_configured");
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(CookieAuthConfig.endpoint()).openConnection();
            if (!(conn instanceof HttpsURLConnection)) {
                // A pin cannot be honoured over plain http, and downgrading
                // silently would hand the session to whoever answers the port.
                Log.w(TAG, "endpoint is not https; refusing");
                return new Result(null, "not_https");
            }
            PinnedHttps.apply((HttpsURLConnection) conn, CookieAuthConfig.pin());

            conn.setConnectTimeout(CookieAuthConfig.CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(CookieAuthConfig.READ_TIMEOUT_MS);
            conn.setRequestProperty("Authorization", "Bearer " + CookieAuthConfig.token());

            int code = conn.getResponseCode();
            if (code != 200) {
                // The service answers 404 for a bad token, on purpose.
                Log.w(TAG, "fetch failed: http " + code);
                return new Result(null, "http_" + code);
            }

            String body = readAll(conn.getInputStream()).trim();
            if (!body.contains("SAPISID=")) {
                Log.w(TAG, "response carries no SAPISID; treating as signed out");
                return new Result(null, "signed_out");
            }

            // Length only. This is a full Google session and must never be logged.
            Log.i(TAG, "fetched cookie header, len=" + body.length());
            return new Result(body, "ok");
        } catch (Throwable e) {
            Log.w(TAG, "fetch failed: " + e);
            // The class name alone separates a refused connection from a
            // timeout from a pin mismatch, without echoing anything sensitive.
            return new Result(null, e.getClass().getSimpleName());
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

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            // A cookie header is a few kilobytes. Much more is not our service.
            if (out.size() > MAX_BODY) {
                throw new Exception("response too large");
            }
        }
        return out.toString("UTF-8");
    }
}
