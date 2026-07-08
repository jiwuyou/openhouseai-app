package com.termux.app.openhouse.files.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.termux.app.openhouse.editor.OpenHouseEditorContract;
import com.termux.app.openhouse.files.model.FileItem;

import java.io.File;

public final class OpenHouseFilesEditorIntents {

    private OpenHouseFilesEditorIntents() {
    }

    public static Intent createOpenIntent(Context context, FileItem item, File repositoryExportFile, String sourceSpaceId) {
        if (repositoryExportFile != null) {
            return createRepositoryExportIntent(context, item, repositoryExportFile, sourceSpaceId);
        }
        String nativeLocation = item == null ? "" : item.getNativeLocation();
        if (isContentUri(nativeLocation)) {
            return createContentUriIntent(context, item, Uri.parse(nativeLocation), sourceSpaceId);
        }
        return createLocalPathIntent(context, item, resolveLocalFile(item), sourceSpaceId);
    }

    public static Intent createLocalPathIntent(Context context, FileItem item, File localFile, String sourceSpaceId) {
        Intent intent = OpenHouseEditorContract.createOpenPathIntent(context, localFile, item == null ? null : item.getMimeType());
        addCommonMetadata(intent, item, sourceSpaceId);
        return intent;
    }

    public static Intent createContentUriIntent(Context context, FileItem item, Uri uri, String sourceSpaceId) {
        boolean readOnly = item == null || !item.isWritable();
        Intent intent = OpenHouseEditorContract.createOpenUriIntent(
            context,
            uri,
            item == null ? null : item.getDisplayName(),
            item == null ? null : item.getMimeType(),
            readOnly);
        addCommonMetadata(intent, item, sourceSpaceId);
        return intent;
    }

    public static Intent createRepositoryExportIntent(Context context, FileItem item, File localFile, String sourceSpaceId) {
        Intent intent = OpenHouseEditorContract.createOpenRepositoryExportIntent(
            context,
            localFile,
            item == null ? null : item.getDisplayName(),
            item == null ? null : item.getMimeType(),
            item == null ? null : item.getNativeLocation(),
            sourceSpaceId,
            item == null ? null : item.getId());
        addCommonMetadata(intent, item, sourceSpaceId);
        intent.putExtra(OpenHouseEditorContract.EXTRA_REPOSITORY_EXPORT, true);
        return intent;
    }

    private static void addCommonMetadata(Intent intent, FileItem item, String sourceSpaceId) {
        if (intent == null || item == null) return;
        putIfPresent(intent, OpenHouseEditorContract.EXTRA_DISPLAY_NAME, item.getDisplayName());
        putIfPresent(intent, OpenHouseEditorContract.EXTRA_MIME_TYPE, item.getMimeType());
        putIfPresent(intent, OpenHouseEditorContract.EXTRA_NATIVE_LOCATION, item.getNativeLocation());
        putIfPresent(intent, OpenHouseEditorContract.EXTRA_ANDROID_DISPLAY_LOCATION, item.getNativeLocation());
        putIfPresent(intent, OpenHouseEditorContract.EXTRA_SOURCE_SPACE_ID, sourceSpaceId);
        putIfPresent(intent, OpenHouseEditorContract.EXTRA_SOURCE_FILE_ID, item.getId());
        if (item.getSize() >= 0) {
            intent.putExtra(OpenHouseEditorContract.EXTRA_SIZE_BYTES, item.getSize());
        }
        intent.putExtra(OpenHouseEditorContract.EXTRA_READ_ONLY, !item.isWritable());
    }

    private static File resolveLocalFile(FileItem item) {
        if (item == null) return null;
        String nativeLocation = item.getNativeLocation();
        if (isFileUri(nativeLocation)) {
            String path = Uri.parse(nativeLocation).getPath();
            return path == null ? null : new File(path);
        }
        if (nativeLocation != null && nativeLocation.startsWith("/")) {
            return new File(nativeLocation);
        }
        return null;
    }

    private static void putIfPresent(Intent intent, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            intent.putExtra(key, value);
        }
    }

    private static boolean isFileUri(String value) {
        try {
            return value != null && "file".equalsIgnoreCase(Uri.parse(value).getScheme());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isContentUri(String value) {
        return value != null && value.regionMatches(true, 0, "content://", 0, "content://".length());
    }
}
