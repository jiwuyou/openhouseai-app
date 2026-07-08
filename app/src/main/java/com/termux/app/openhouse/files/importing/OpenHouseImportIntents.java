package com.termux.app.openhouse.files.importing;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.termux.shared.net.uri.UriUtils;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

import java.io.File;
import java.net.URLConnection;

public final class OpenHouseImportIntents {

    private static final String FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider";
    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    private OpenHouseImportIntents() {
    }

    public static Intent createShareAiDescriptionIntent(OpenHouseImportedFile importedFile) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "OpenHouse 文件说明: " + importedFile.getDisplayName());
        intent.putExtra(Intent.EXTRA_TEXT, importedFile.buildAiDescription());
        return intent;
    }

    public static Intent createOpenExternallyIntent(Context context, OpenHouseImportedFile importedFile) {
        File file = importedFile.getFile();
        Uri uri = FileProvider.getUriForFile(
            context,
            context.getPackageName() + FILE_PROVIDER_AUTHORITY_SUFFIX,
            file
        );
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, resolveMimeType(importedFile));
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    public static Intent createLegacyEditorServiceIntent(String packageName, Class<?> serviceClass, String editorProgramPath, File file) {
        Intent intent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, UriUtils.getFileUri(editorProgramPath));
        intent.setClassName(packageName, serviceClass.getName());
        intent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, new String[]{file.getAbsolutePath()});
        return intent;
    }

    public static Intent createLegacyOpenReceiveDirServiceIntent(String packageName, Class<?> serviceClass, String receiveDir) {
        Intent intent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE);
        intent.setClassName(packageName, serviceClass.getName());
        intent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, receiveDir);
        return intent;
    }

    static String resolveMimeType(OpenHouseImportedFile importedFile) {
        String mimeType = importedFile.getMimeType();
        if (mimeType != null && !mimeType.trim().isEmpty()) {
            return mimeType;
        }
        mimeType = URLConnection.guessContentTypeFromName(importedFile.getDisplayName());
        if (mimeType != null && !mimeType.trim().isEmpty()) {
            return mimeType;
        }
        return DEFAULT_MIME_TYPE;
    }
}
