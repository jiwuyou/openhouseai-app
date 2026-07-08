package com.termux.app.openhouse.editor;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;

public final class OpenHouseEditorContract {

    public static final String ACTION_OPEN = "com.termux.openhouse.editor.action.OPEN";
    public static final String ACTION_EDIT = "com.termux.openhouse.editor.action.EDIT";

    public static final String EXTRA_FILE_PATH = "com.termux.openhouse.editor.extra.FILE_PATH";
    public static final String EXTRA_FILE_URI = "com.termux.openhouse.editor.extra.FILE_URI";
    public static final String EXTRA_DISPLAY_NAME = "com.termux.openhouse.editor.extra.DISPLAY_NAME";
    public static final String EXTRA_MIME_TYPE = "com.termux.openhouse.editor.extra.MIME_TYPE";
    public static final String EXTRA_SIZE_BYTES = "com.termux.openhouse.editor.extra.SIZE_BYTES";
    public static final String EXTRA_NATIVE_LOCATION = "com.termux.openhouse.editor.extra.NATIVE_LOCATION";
    public static final String EXTRA_ANDROID_DISPLAY_LOCATION = "com.termux.openhouse.editor.extra.ANDROID_DISPLAY_LOCATION";
    public static final String EXTRA_TERMUX_PATH = "com.termux.openhouse.editor.extra.TERMUX_PATH";
    public static final String EXTRA_UBUNTU_PATH = "com.termux.openhouse.editor.extra.UBUNTU_PATH";
    public static final String EXTRA_WORKSPACE_PATH = "com.termux.openhouse.editor.extra.WORKSPACE_PATH";
    public static final String EXTRA_SOURCE_SPACE_ID = "com.termux.openhouse.editor.extra.SOURCE_SPACE_ID";
    public static final String EXTRA_SOURCE_FILE_ID = "com.termux.openhouse.editor.extra.SOURCE_FILE_ID";
    public static final String EXTRA_READ_ONLY = "com.termux.openhouse.editor.extra.READ_ONLY";
    public static final String EXTRA_REPOSITORY_EXPORT = "com.termux.openhouse.editor.extra.REPOSITORY_EXPORT";

    public static final String EXTRA_SAVED_FILE_PATH = "com.termux.openhouse.editor.extra.SAVED_FILE_PATH";
    public static final String EXTRA_SAVED_FILE_URI = "com.termux.openhouse.editor.extra.SAVED_FILE_URI";
    public static final String EXTRA_BACKUP_FILE_PATH = "com.termux.openhouse.editor.extra.BACKUP_FILE_PATH";
    public static final String EXTRA_SAVED_AT_MILLIS = "com.termux.openhouse.editor.extra.SAVED_AT_MILLIS";

    private OpenHouseEditorContract() {
    }

    public static Intent createOpenPathIntent(Context context, File file, String mimeType) {
        return createOpenPathIntent(context, file, mimeType, false);
    }

    public static Intent createOpenPathIntent(Context context, File file, String mimeType, boolean readOnly) {
        Intent intent = baseIntent(context, ACTION_OPEN);
        if (file != null) {
            intent.putExtra(EXTRA_FILE_PATH, file.getAbsolutePath());
            intent.putExtra(EXTRA_DISPLAY_NAME, file.getName());
            intent.putExtra(EXTRA_SIZE_BYTES, file.isFile() ? file.length() : -1L);
        }
        intent.putExtra(EXTRA_READ_ONLY, readOnly);
        putIfPresent(intent, EXTRA_MIME_TYPE, mimeType);
        if (mimeType != null && !mimeType.trim().isEmpty()) {
            intent.setType(mimeType);
        }
        return intent;
    }

    public static Intent createOpenUriIntent(Context context, Uri uri, String displayName, String mimeType) {
        return createOpenUriIntent(context, uri, displayName, mimeType, false);
    }

    public static Intent createOpenUriIntent(Context context, Uri uri, String displayName, String mimeType, boolean readOnly) {
        Intent intent = baseIntent(context, ACTION_OPEN);
        if (uri != null) {
            if (mimeType != null && !mimeType.trim().isEmpty()) {
                intent.setDataAndType(uri, mimeType);
            } else {
                intent.setData(uri);
            }
            intent.putExtra(EXTRA_FILE_URI, uri.toString());
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (!readOnly) {
                flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            }
            intent.addFlags(flags);
        }
        intent.putExtra(EXTRA_READ_ONLY, readOnly);
        putIfPresent(intent, EXTRA_DISPLAY_NAME, displayName);
        putIfPresent(intent, EXTRA_MIME_TYPE, mimeType);
        if (uri == null && mimeType != null && !mimeType.trim().isEmpty()) {
            intent.setType(mimeType);
        }
        return intent;
    }

    public static Intent createOpenRepositoryExportIntent(
        Context context,
        File localFile,
        String displayName,
        String mimeType,
        String nativeLocation,
        String sourceSpaceId,
        String sourceFileId
    ) {
        Intent intent = createOpenPathIntent(context, localFile, mimeType);
        intent.putExtra(EXTRA_REPOSITORY_EXPORT, true);
        putIfPresent(intent, EXTRA_DISPLAY_NAME, displayName);
        putIfPresent(intent, EXTRA_NATIVE_LOCATION, nativeLocation);
        putIfPresent(intent, EXTRA_SOURCE_SPACE_ID, sourceSpaceId);
        putIfPresent(intent, EXTRA_SOURCE_FILE_ID, sourceFileId);
        return intent;
    }

    public static Intent baseIntent(Context context, String action) {
        Intent intent = new Intent(action == null ? ACTION_OPEN : action);
        intent.setClass(context, OpenHouseEditorActivity.class);
        return intent;
    }

    static void putIfPresent(Intent intent, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            intent.putExtra(key, value);
        }
    }
}
