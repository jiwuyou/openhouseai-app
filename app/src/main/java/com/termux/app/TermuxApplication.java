package com.termux.app;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.webkit.WebView;

import com.termux.BuildConfig;
import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxBootstrap;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.shared.termux.shell.am.TermuxAmSocketServer;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.ai.assistance.operit.rescue.resources.ApkResourceOfferStore;
import com.termux.app.operit.init.OperitHostBootstrap;
import com.openhouse.host.termux.TermuxProductHost;
import com.wuxianpi.openhouse.feature.OpenHouseFeatureHost;
import com.wuxianpi.openhouse.feature.OpenHouseFeatureHostProvider;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class TermuxApplication extends Application implements OpenHouseFeatureHostProvider {

    private static final String LOG_TAG = "TermuxApplication";
    private TermuxProductHost productHost;

    public void onCreate() {
        super.onCreate();

        Context context = getApplicationContext();

        configureProcessScopedWebViewDataDirectory(context);
        productHost = new TermuxProductHost(context);
        productHost.install();
        OperitHostBootstrap.installHostBridge(context);

        // Set crash handler for the app
        TermuxCrashUtils.setDefaultCrashHandler(this);

        // Set log config for the app
        setLogConfig(context);

        Logger.logDebug("Starting Application");

        // Set TermuxBootstrap.TERMUX_APP_PACKAGE_MANAGER and TermuxBootstrap.TERMUX_APP_PACKAGE_VARIANT
        TermuxBootstrap.setTermuxPackageManagerAndVariant(BuildConfig.TERMUX_PACKAGE_VARIANT);

        // Init app wide SharedProperties loaded from termux.properties
        TermuxAppSharedProperties properties = TermuxAppSharedProperties.init(context);

        // Init app wide shell manager
        TermuxShellManager shellManager = TermuxShellManager.init(context);

        // Set NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(properties.getNightMode());

        // Check and create termux files directory. If failed to access it like in case of secondary
        // user or external sd card installation, then don't run files directory related code
        Error error = TermuxFileUtils.isTermuxFilesDirectoryAccessible(this, true, true);
        boolean isTermuxFilesDirectoryAccessible = error == null;
        if (isTermuxFilesDirectoryAccessible) {
            Logger.logInfo(LOG_TAG, "Termux files directory is accessible");

            error = TermuxFileUtils.isAppsTermuxAppDirectoryAccessible(true, true);
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, "Create apps/termux-app directory failed\n" + error);
                return;
            }

            // Setup termux-am-socket server
            TermuxAmSocketServer.setupTermuxAmSocketServer(context);
        } else {
            Logger.logErrorExtended(LOG_TAG, "Termux files directory is not accessible\n" + error);
        }

        // Init TermuxShellEnvironment constants and caches after everything has been setup including termux-am-socket server
        TermuxShellEnvironment.init(this);

        if (isTermuxFilesDirectoryAccessible) {
            TermuxShellEnvironment.writeEnvironmentToFile(this);
            // APK resources remain private assets until Rescue AI explicitly stages a verified
            // difference. Recording an offer here must never copy or overwrite Termux files.
            ApkResourceOfferStore.recordCurrentApk(context);
        }
    }

    @Override
    public OpenHouseFeatureHost openHouseFeatureHost() {
        if (productHost == null) {
            productHost = new TermuxProductHost(this);
            productHost.install();
        }
        return productHost;
    }

    private static void configureProcessScopedWebViewDataDirectory(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || context == null) {
            return;
        }

        String packageName = context.getPackageName();
        String processName = getCurrentProcessName();
        if (processName == null || processName.equals(packageName) || !processName.startsWith(packageName + ":")) {
            return;
        }

        String suffix = processName.substring(packageName.length() + 1);
        if (suffix.trim().isEmpty()) {
            return;
        }

        try {
            WebView.setDataDirectorySuffix(suffix);
            Logger.logInfo(LOG_TAG, "Configured WebView data directory suffix for process: " + processName);
        } catch (Throwable throwable) {
            Logger.logError(LOG_TAG, "Failed to configure WebView data directory suffix for " + processName + ": " + throwable.getMessage());
        }
    }

    private static String getCurrentProcessName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            String processName = Application.getProcessName();
            if (processName != null && !processName.trim().isEmpty()) {
                return processName;
            }
        }

        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/cmdline"))) {
            String processName = reader.readLine();
            if (processName != null) {
                processName = processName.replace('\u0000', ' ').trim();
                if (!processName.isEmpty()) {
                    return processName;
                }
            }
        } catch (IOException ignored) {
            // Best effort only; the main process can safely keep WebView's default data directory.
        }
        return null;
    }

    public static void setLogConfig(Context context) {
        Logger.setDefaultLogTag(TermuxConstants.TERMUX_APP_NAME);

        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context);
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel());
    }

}
