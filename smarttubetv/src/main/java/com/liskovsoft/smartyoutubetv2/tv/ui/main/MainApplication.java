package com.liskovsoft.smartyoutubetv2.tv.ui.main;

import android.os.Build.VERSION;
import android.util.Log;

import androidx.multidex.MultiDexApplication;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.AddDeviceView;
import com.liskovsoft.smartyoutubetv2.common.app.views.AppDialogView;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelUploadsView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelView;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.SearchView;
import com.liskovsoft.smartyoutubetv2.common.app.views.SignInView;
import com.liskovsoft.smartyoutubetv2.common.app.views.SplashView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.app.views.WebBrowserView;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.NetworkData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.tv.ui.adddevice.AddDeviceActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.BrowseActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.channel.ChannelActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.channeluploads.ChannelUploadsActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.dialogs.AppDialogActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.dialogs.AppDialogActivityOpaque;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.PlaybackActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.search.tags.SearchTagsActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.signin.SignInActivity;
import com.liskovsoft.smartyoutubetv2.common.cookieauth.CookieAuthClient;
import com.liskovsoft.smartyoutubetv2.common.cookieauth.CookieAuthConfig;
import com.liskovsoft.smartyoutubetv2.common.diagnostics.DiagnosticsConfig.Level;
import com.liskovsoft.smartyoutubetv2.common.diagnostics.DiagnosticsReporter;
import com.liskovsoft.smartyoutubetv2.tv.ui.webbrowser.WebBrowserActivity;

import com.liskovsoft.youtubeapi.app.CookieAuthStore;

import org.conscrypt.Conscrypt;

import java.lang.Thread.UncaughtExceptionHandler;
import java.security.Provider;
import java.security.Security;

public class MainApplication extends MultiDexApplication { // fix: Didn't find class "com.google.firebase.provider.FirebaseInitProvider"
    private static final String COOKIE_TAG = "CookieAuth";

    static {
        // fix youtube bandwidth throttling (best - false)???
        // false is better for streams (less buffering)
        System.setProperty("http.keepAlive", "false");
        // fix ipv6 infinite video buffering???
        // Better to remove this fix at all. Users complain about infinite loading.
        //System.setProperty("java.net.preferIPv6Addresses", "true");
        // Another IPv6 fix (no effect)
        // https://stackoverflow.com/questions/1920623/sometimes-httpurlconnection-getinputstream-executes-too-slowly
        //System.setProperty("java.net.preferIPv4Stack" , "true");
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // ByeByeDPI fix
        // https://android-review.googlesource.com/c/platform/external/conscrypt/+/89408/
        // NOTE: Android 10+ (API 29+) uses system Conscrypt TLS; custom Security providers are unnecessary
        // NOTE: May cause 'Unexpected playback error null'
        //if (Build.VERSION.SDK_INT < 29 && Conscrypt.isAvailable()) {
        //    Security.insertProviderAt(Conscrypt.newProvider(), 1);
        //}

        // Important: Initialize the native Conscrypt provider BEFORE reading any configs/SharedPreferences.
        // Otherwise, early disk I/O shifts the ClassLoader on some Android TV devices, causing silent JNI linking errors.
        Provider conscryptProvider = null;
        try {
            conscryptProvider = Conscrypt.newProvider();
        } catch (Throwable e) {
            // UnsatisfiedLinkError
        }

        if (conscryptProvider != null && NetworkData.instance(this).isConscryptEnabled()) {
            try {
                Security.insertProviderAt(conscryptProvider, 1);
            } catch (Throwable e) {
                // UnsatisfiedLinkError
            }
        }

        DiagnosticsReporter.attachToApi();

        loadSessionCookies();

        setupGlobalExceptionHandler();
        setupViewManager();
    }

    /**
     * Hand the youtube api a signed-in web session, without ever blocking.
     *
     * An earlier version waited on the first fetch so that nothing could play
     * before the cookies landed. That was the wrong trade: when the service is
     * unreachable the wait is the full timeout, on the main thread, during
     * onCreate -- the ui simply freezes, and the user is left pressing buttons
     * at a dead screen. A video that fails because the cookies have not arrived
     * yet is a far smaller problem, and the next one works.
     *
     * Nothing is cached to disk. Google rotates the session as the holder
     * browses, so anything written down is a snapshot of a session that has
     * moved on. The in-memory copy is deliberately kept across a failed
     * refresh: it is probably still good, and dropping it would turn a blip in
     * the service into a device that cannot play at all.
     */
    private void loadSessionCookies() {
        if (!CookieAuthConfig.isConfigured()) {
            return;
        }

        Thread loader = new Thread(new Runnable() {
            @Override
            public void run() {
                CookieAuthClient client = new CookieAuthClient();
                // Retry quickly at first: at boot the app can easily win the
                // race against the network coming up, and waiting the full
                // refresh period for that would leave the device unusable.
                long[] backoffMs = { 0, 10_000, 30_000, 60_000 };
                int attempt = 0;

                while (true) {
                    long wait = attempt < backoffMs.length
                            ? backoffMs[attempt]
                            : CookieAuthConfig.REFRESH_MINUTES * 60L * 1000L;
                    if (wait > 0) {
                        try {
                            Thread.sleep(wait);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }

                    CookieAuthClient.Result result = client.fetchWithReason();
                    if (result.header != null) {
                        CookieAuthStore.setCookies(result.header);
                        DiagnosticsReporter.report(Level.BASIC, "cookies_loaded",
                                "cookie_len", result.header.length(),
                                "attempt", attempt);
                        // Settle into the refresh cadence once it works.
                        attempt = backoffMs.length;
                    } else {
                        DiagnosticsReporter.report(Level.BASIC, "cookies_failed",
                                "reason", result.reason,
                                "attempt", attempt);
                        attempt++;
                    }

                    Log.i(COOKIE_TAG, "cookie auth enabled=" + CookieAuthStore.isEnabled());
                }
            }
        }, "cookie-loader");
        loader.setDaemon(true);
        loader.start();
    }

    private void setupViewManager() {
        ViewManager viewManager = ViewManager.instance(this);

        Class<? extends AppDialogActivity> dialogClazz;

        if (VERSION.SDK_INT == 26
                && Helpers.equalsAny(Helpers.getCrashlyticsDeviceName(), "4S806_Z51S1 (Panasonic)")) {
            // The fix: Only fullscreen opaque activities can request orientation
            dialogClazz = AppDialogActivityOpaque.class;
        } else {
            dialogClazz = AppDialogActivity.class;
        }

        viewManager.setRoot(BrowseActivity.class);
        viewManager.register(SplashView.class, SplashActivity.class); // no parent, because it's root activity
        viewManager.register(BrowseView.class, BrowseActivity.class); // no parent, because it's root activity
        viewManager.register(PlaybackView.class, PlaybackActivity.class, BrowseActivity.class);
        viewManager.register(AppDialogView.class, dialogClazz, BrowseActivity.class);
        viewManager.register(SearchView.class, SearchTagsActivity.class, BrowseActivity.class);
        viewManager.register(SignInView.class, SignInActivity.class, BrowseActivity.class);
        viewManager.register(AddDeviceView.class, AddDeviceActivity.class, BrowseActivity.class);
        viewManager.register(ChannelView.class, ChannelActivity.class, BrowseActivity.class);
        viewManager.register(ChannelUploadsView.class, ChannelUploadsActivity.class, BrowseActivity.class);
        viewManager.register(WebBrowserView.class, WebBrowserActivity.class, BrowseActivity.class);
    }

    private void setupGlobalExceptionHandler() {
        UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();

        if (defaultHandler == null) {
            return;
        }

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            if (shouldIgnore(e)) {
                return;
            }

            applyCrashFixes(e);
            //e = wrapWithAdditionalInfo(e);

            defaultHandler.uncaughtException(t, e);
        });
    }

    private boolean shouldIgnore(Throwable e) {
        if (Helpers.containsAny(e.getMessage(), "KatnissVoiceInteractionService", "ListenableFuture", "Missing android.support.FILE_PROVIDER_PATHS meta-data")
                || e.getClass().getName().startsWith("org.chromium")) {
            // IllegalStateException: Not allowed to start service Intent { act=android.service.voice.VoiceInteractionService
            // cmp=com.google.android.katniss/.search.serviceapi.KatnissVoiceInteractionService (has extras) }:
            // app is in background uid UidRecord{40e7240 u0a19 CEM idle change:cached procs:1 seq(0,0,0)}

            // java.lang.NoSuchMethodError: No interface method addListener(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)V
            // in class Lcom/google/common/util/concurrent/ListenableFuture; or its super classes
            // (declaration of 'com.google.common.util.concurrent.ListenableFuture' appears in /system/framework/libsetting.jar)

            // Fatal Exception: org.chromium.base.JniAndroid$UncaughtExceptionException
            // 1) Caused by java.lang.InterruptedException
            // 2) Caused by java.lang.SecurityException: The calling process has already registered an InputDevicesChangedListener.

            // IllegalArgumentException: Missing android.support.FILE_PROVIDER_PATHS meta-data (Shield Android TV 95%, MiTV-AFKR0 5%)
            return true;
        }

        return false;
    }

    private Throwable wrapWithAdditionalInfo(Throwable e) {
        if (Helpers.equalsAny(e.getMessage(),
                "parameter must be a descendant of this view",
                "Attempt to invoke virtual method 'android.view.ViewGroup$LayoutParams android.view.View.getLayoutParams()' on a null object reference")) {
            Class<?> view = ViewManager.instance(getApplicationContext()).getTopView();
            BrowseSection section = null;

            if (view == BrowseView.class) {
                section = BrowsePresenter.instance(getApplicationContext()).getCurrentSection();
            }

            e = new RuntimeException("A crash in the view " + view.getSimpleName() + ", section id " + (section != null ? section.getId() : "-1"), e);
        }
        return e;
    }

    private void applyCrashFixes(Throwable e) {
        if (e instanceof OutOfMemoryError || e.getCause() instanceof OutOfMemoryError) {
            Class<?> view = ViewManager.instance(getApplicationContext()).getTopView();
            if (view == PlaybackView.class) {
                PlayerTweaksData tweaksData = PlayerTweaksData.instance(getApplicationContext());
                PlayerData playerData = PlayerData.instance(getApplicationContext());
                int playerDataSource = tweaksData.getPlayerDataSource();
                int videoBufferType = playerData.getVideoBufferType();
                if (playerDataSource == PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP) {
                    tweaksData.setPlayerDataSource(PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT);
                    tweaksData.persistNow();
                } else if (videoBufferType == PlayerData.BUFFER_HIGH || videoBufferType == PlayerData.BUFFER_HIGHEST) {
                    playerData.setVideoBufferType(PlayerData.BUFFER_MEDIUM);
                    playerData.persistNow();
                }
            }
        }
    }
}
