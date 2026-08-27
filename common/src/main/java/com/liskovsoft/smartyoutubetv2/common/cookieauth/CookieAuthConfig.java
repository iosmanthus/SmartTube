package com.liskovsoft.smartyoutubetv2.common.cookieauth;

import com.liskovsoft.smartyoutubetv2.common.BuildConfig;
import com.liskovsoft.smartyoutubetv2.common.net.PinnedHttps;

/**
 * Where the YouTube session cookies come from.
 *
 * All three values are per-deployment -- a box on somebody's own lan, its
 * certificate, and a secret -- so none of them live in the source. They arrive
 * as gradle properties at build time (see smarttubetv/build.gradle) and default
 * to empty, which leaves the whole cookie path switched off. That is the right
 * default for a build that ends up on a device it was not made for: it does
 * nothing rather than reaching for a session it has no business holding.
 *
 * There is deliberately no runtime config file. It would be a second, weaker
 * way to point the app at an endpoint, and on the devices this targets there is
 * no way to place a file anyway -- no adb, and the vendor rom refuses read-only
 * usb volumes.
 *
 * Diagnostics are configured separately, with their own endpoint, token and
 * certificate, so neither switch touches the other.
 */
public final class CookieAuthConfig {
    /** Long enough to outlast a slow lan round trip, short enough not to stall startup. */
    public static final int CONNECT_TIMEOUT_MS = 4000;
    public static final int READ_TIMEOUT_MS = 8000;

    /**
     * How often to re-ask for the header.
     *
     * Google rotates the session cookies as the holder browses. What we hold
     * stays valid for a while after a rotation, but not forever, so re-ask on a
     * timer rather than discovering it when somebody presses play.
     */
    public static final int REFRESH_MINUTES = 20;

    private CookieAuthConfig() {
    }

    public static String endpoint() {
        return BuildConfig.COOKIE_AUTH_ENDPOINT;
    }

    public static String token() {
        return BuildConfig.COOKIE_AUTH_TOKEN;
    }

    /** Server certificate sha-256, upper-case hex, no separators. */
    public static String pin() {
        return PinnedHttps.normalizePin(BuildConfig.COOKIE_AUTH_PIN);
    }

    /** All three or nothing: a partial configuration is a misconfiguration. */
    public static boolean isConfigured() {
        return !endpoint().isEmpty() && !token().isEmpty() && !pin().isEmpty();
    }
}
