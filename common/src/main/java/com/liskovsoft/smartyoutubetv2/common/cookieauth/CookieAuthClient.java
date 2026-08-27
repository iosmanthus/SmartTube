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

    /** The Cookie header, or null. Blocking; never call this on the main thread. */
    public String fetch() {
        if (!CookieAuthConfig.isConfigured()) {
            return null;
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(CookieAuthConfig.endpoint()).openConnection();
            if (!(conn instanceof HttpsURLConnection)) {
                // A pin cannot be honoured over plain http, and downgrading
                // silently would hand the session to whoever answers the port.
                Log.w(TAG, "endpoint is not https; refusing");
                return null;
            }
            PinnedHttps.apply((HttpsURLConnection) conn, CookieAuthConfig.pin());

            conn.setConnectTimeout(CookieAuthConfig.CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(CookieAuthConfig.READ_TIMEOUT_MS);
            conn.setRequestProperty("Authorization", "Bearer " + CookieAuthConfig.token());

            int code = conn.getResponseCode();
            if (code != 200) {
                // The service answers 404 for a bad token, on purpose.
                Log.w(TAG, "fetch failed: http " + code);
                return null;
            }

            String body = readAll(conn.getInputStream()).trim();
            if (!body.contains("SAPISID=")) {
                Log.w(TAG, "response carries no SAPISID; treating as signed out");
                return null;
            }

            // Length only. This is a full Google session and must never be logged.
            Log.i(TAG, "fetched cookie header, len=" + body.length());
            return body;
        } catch (Throwable e) {
            Log.w(TAG, "fetch failed: " + e);
            return null;
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
