package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import com.liskovsoft.smartyoutubetv2.common.BuildConfig;
import com.liskovsoft.smartyoutubetv2.common.net.PinnedHttps;

/**
 * Whether, where and how much to report about playback.
 *
 * A tv is a bad place to debug from: there is no adb here, the vendor rom drops
 * DEBUG-level logs, and nobody is going to read logcat off a television. So the
 * device pushes structured events to a collector instead of being polled.
 *
 * Configured entirely at build time and off by default. The collector has its
 * own endpoint, its own token and its own certificate, separate from the cookie
 * service, so neither one can be reached with the other's credentials.
 */
public final class DiagnosticsConfig {
    /** How much detail leaves the device. Each level includes the ones above it. */
    public enum Level {
        /** Nothing is sent. The default. */
        OFF,
        /** Which client was used, whether it carried a token, what came back. */
        BASIC,
        /** Adds request paths (no query) and the n parameter before and after. */
        VERBOSE,
        /**
         * Adds media urls.
         *
         * These are signed and directly fetchable by anyone who has them, for as
         * long as they last. Only turn this on against a collector you own, and
         * only while actually chasing something.
         */
        FULL;

        static Level parse(String name) {
            for (Level level : values()) {
                if (level.name().equalsIgnoreCase(name)) {
                    return level;
                }
            }
            return OFF;
        }
    }

    public static final int CONNECT_TIMEOUT_MS = 4000;
    public static final int READ_TIMEOUT_MS = 8000;

    /** Batched rather than one request per event; a busy player emits bursts. */
    public static final int FLUSH_INTERVAL_MS = 5000;

    /**
     * Cap on unsent events. When the collector is unreachable the queue fills,
     * and dropping the oldest is better than growing without bound on a device
     * with a few hundred megabytes of heap.
     */
    public static final int QUEUE_LIMIT = 500;

    private DiagnosticsConfig() {
    }

    public static String endpoint() {
        return BuildConfig.DIAG_ENDPOINT;
    }

    public static String token() {
        return BuildConfig.DIAG_TOKEN;
    }

    public static String pin() {
        return PinnedHttps.normalizePin(BuildConfig.DIAG_PIN);
    }

    public static Level level() {
        return Level.parse(BuildConfig.DIAG_LEVEL);
    }

    public static boolean isEnabled() {
        return level() != Level.OFF
                && !endpoint().isEmpty() && !token().isEmpty() && !pin().isEmpty();
    }

    /** True when the configured level is at least {@code wanted}. */
    public static boolean atLeast(Level wanted) {
        return isEnabled() && level().ordinal() >= wanted.ordinal();
    }
}
