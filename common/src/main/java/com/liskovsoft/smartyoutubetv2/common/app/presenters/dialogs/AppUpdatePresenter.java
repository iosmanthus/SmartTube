package com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs;

import com.liskovsoft.smartyoutubetv2.common.BuildConfig;
import android.net.Uri;
import com.liskovsoft.sharedutils.helpers.FileHelpers;
import com.liskovsoft.smartyoutubetv2.common.diagnostics.DiagnosticsConfig;
import com.liskovsoft.smartyoutubetv2.common.diagnostics.DiagnosticsConfig.Level;
import com.liskovsoft.smartyoutubetv2.common.diagnostics.DiagnosticsReporter;
import android.annotation.SuppressLint;
import android.content.Context;
import com.liskovsoft.appupdatechecker2.AppUpdateChecker;
import com.liskovsoft.appupdatechecker2.AppUpdateCheckerListener;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.utils.LoadingManager;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import java.util.ArrayList;
import java.util.List;

public class AppUpdatePresenter extends BasePresenter<Void> implements AppUpdateCheckerListener {
    @SuppressLint("StaticFieldLeak")
    private static AppUpdatePresenter sInstance;
    private final AppUpdateChecker mUpdateChecker;
    private final AppDialogPresenter mSettingsPresenter;
    private final String[] mUpdateManifestUrls;
    private boolean mIsForceCheck;

    public AppUpdatePresenter(Context context) {
        super(context);
        mUpdateChecker = new AppUpdateChecker(context, this);
        mSettingsPresenter = AppDialogPresenter.instance(context);
        mUpdateManifestUrls = withConfiguredUrl(context.getResources().getStringArray(R.array.update_urls));
    }

    /**
     * Put the build's own update manifest ahead of the shipped ones.
     *
     * The checker tries each url in turn and uses the first that answers, so
     * prepending is enough to redirect a fork's devices at its own builds while
     * leaving upstream as the fallback. Empty unless the build sets it.
     */
    private static String[] withConfiguredUrl(String[] urls) {
        String configured = BuildConfig.UPDATE_URL;

        if (configured == null || configured.isEmpty()) {
            return urls;
        }

        String[] out = new String[urls.length + 1];
        out[0] = configured;
        System.arraycopy(urls, 0, out, 1, urls.length);

        return out;
    }

    public static AppUpdatePresenter instance(Context context) {
        if (sInstance == null) {
            sInstance = new AppUpdatePresenter(context);
        }

        sInstance.setContext(context);

        return sInstance;
    }

    public static void unhold() {
        sInstance = null;
    }

    public void start(boolean forceCheck) {
        mIsForceCheck = forceCheck;

        if (forceCheck) {
            LoadingManager.showLoading(getContext(), true);
            mUpdateChecker.forceCheckForUpdates(mUpdateManifestUrls);
        } else {
            mUpdateChecker.checkForUpdates(mUpdateManifestUrls);
        }
    }

    @Override
    public void onUpdateFound(String versionName, List<String> changelog, String apkPath) {
        if (mIsForceCheck) {
            LoadingManager.showLoading(getContext(), false);
            showUpdateDialog(versionName, changelog, apkPath);
        } else if (GeneralData.instance(getContext()).isOldUpdateNotificationsEnabled()) {
            showUpdateDialog(versionName, changelog, apkPath);
        } else {
            pinUpdateSection(versionName, changelog, apkPath);
        }
    }

    @Override
    public void onUpdateError(Exception error) {
        if (mIsForceCheck) {
            LoadingManager.showLoading(getContext(), false);

            if (AppUpdateCheckerListener.LATEST_VERSION.equals(error.getMessage())) {
                MessageHelpers.showMessage(getContext(), R.string.update_not_found);
            } else {
                MessageHelpers.showMessage(getContext(), String.format("%s: %s", getContext().getString(R.string.update_error),
                        error.getCause() != null ? error.getCause().getMessage() : error.getMessage()));
            }
        }

        onFinish();
    }

    private void showUpdateDialog(String versionName, List<String> changelog, String apkPath) {
        // Don't show update dialog if the player opened or the app is collapsed
        if (getContext() == null || getViewManager().isPlayerInForeground() || !Utils.isAppInForegroundFixed()) {
            return;
        }

        mSettingsPresenter.appendSingleButton(
                UiOptionItem.from(getContext().getString(R.string.install_update), optionItem -> {
                    GeneralData.instance(getContext()).setChangelog(changelog);
                    reportInstallAttempt("dialog", apkPath);
                    mUpdateChecker.installUpdate();
                }, false));
        mSettingsPresenter.appendStringsCategory(getContext().getString(R.string.update_changelog), createChangelogOptions(changelog));
        //mSettingsPresenter.appendSingleSwitch(UiOptionItem.from(getContext().getString(R.string.show_again), optionItem -> {
        //    mUpdateChecker.enableUpdateCheck(optionItem.isSelected());
        //}, mUpdateChecker.isUpdateCheckEnabled()));

        //mSettingsPresenter.setOnFinish(getOnFinish());
        mSettingsPresenter.showDialog(String.format("%s %s", getContext().getString(R.string.app_name), versionName), AppUpdatePresenter::unhold);
    }

    private void pinUpdateSection(String versionName, List<String> changelog, String apkPath) {
        // Don't show update dialog if the player opened or the app is collapsed
        if (getContext() == null) {
            return;
        }

        BrowsePresenter.instance(getContext()).pinItem(getContext().getString(R.string.update_found), R.drawable.action_info, new ErrorFragmentData() {
            @Override
            public void onAction() {
                GeneralData.instance(getContext()).setChangelog(changelog);
                reportInstallAttempt("pinned", apkPath);
                mUpdateChecker.installUpdate();
            }

            @Override
            public String getMessage() {
                return String.format("%s %s", getContext().getString(R.string.app_name), versionName) + " " +
                        getContext().getString(R.string.update_changelog) + ":\n" +
                        createChangelog(changelog);
            }

            @Override
            public String getActionText() {
                return getContext().getString(R.string.install_update);
            }
        });
    }

    /**
     * Record what the install had to work with, before it is attempted.
     *
     * Installing an update is the one flow that cannot report on itself: it
     * either replaces the process or leaves no trace at all. Helpers.
     * installPackage() returns silently when FileProvider cannot resolve the
     * apk -- which is exactly what a press that appears to do nothing looks
     * like -- so the inputs are recorded here, while there is still a process
     * to record them from.
     */
    private void reportInstallAttempt(String source, String apkPath) {
        if (!DiagnosticsConfig.isEnabled()) {
            return;
        }

        Context context = getContext();
        java.io.File apk = apkPath != null ? new java.io.File(apkPath) : null;
        String uri;
        try {
            Uri resolved = FileHelpers.getFileUri(context, apkPath);
            uri = resolved != null ? resolved.getScheme() + "://" + resolved.getAuthority() : "null";
        } catch (Throwable e) {
            uri = "threw:" + e.getClass().getSimpleName();
        }

        DiagnosticsReporter.report(Level.BASIC, "update_install",
                "source", source,
                // Whether the presenter had a real Activity matters: an install
                // intent started from the application context becomes its own
                // task and can end up behind the one that started it.
                "context", context == null ? "null" : context.getClass().getSimpleName(),
                "apk_exists", apk != null && apk.exists(),
                "apk_bytes", apk != null && apk.exists() ? apk.length() : 0,
                "apk_dir", apk != null && apk.getParentFile() != null
                        ? apk.getParentFile().getAbsolutePath() : "null",
                "file_uri", uri);
    }

    private List<OptionItem> createChangelogOptions(List<String> changelog) {
        List<OptionItem> options = new ArrayList<>();

        for (String change : changelog) {
            options.add(UiOptionItem.from(change));
        }

        return options;
    }

    private String createChangelog(List<String> changelog) {
        StringBuilder builder = new StringBuilder();

        int maxLines = 30;
        int lineNum = 0;

        for (String change : changelog) {
            if (lineNum > maxLines) {
                break;
            }

            builder.append("- ");
            builder.append(change);
            builder.append("\n");

            lineNum++;
        }

        return builder.toString();
    }
}
