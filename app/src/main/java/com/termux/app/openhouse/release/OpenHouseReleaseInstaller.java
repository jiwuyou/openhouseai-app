package com.termux.app.openhouse.release;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.File;

public final class OpenHouseReleaseInstaller {

    public static final String MIME_TYPE_APK = "application/vnd.android.package-archive";
    private static final String AUTHORITY_SUFFIX = ".release.fileprovider";
    private static final String DOWNLOAD_DIR_NAME = "release_updates";
    private static final String DOWNLOAD_FILE_NAME = "openhouse-release-update.apk";

    private OpenHouseReleaseInstaller() {
    }

    public static File getDownloadFile(Context context) {
        return new File(new File(context.getCacheDir(), DOWNLOAD_DIR_NAME), DOWNLOAD_FILE_NAME);
    }

    public static boolean canRequestPackageInstalls(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
            || context.getPackageManager().canRequestPackageInstalls();
    }

    public static Intent createInstallIntent(Context context, File apkFile) {
        Uri apkUri = FileProvider.getUriForFile(context, getProviderAuthority(context), apkFile);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(apkUri, MIME_TYPE_APK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        intent.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.getPackageName());
        return intent;
    }

    public static Intent createUnknownSourcesSettingsIntent(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.getPackageName())
            );
        }
        return new Intent(Settings.ACTION_SECURITY_SETTINGS);
    }

    private static String getProviderAuthority(Context context) {
        return context.getPackageName() + AUTHORITY_SUFFIX;
    }
}
