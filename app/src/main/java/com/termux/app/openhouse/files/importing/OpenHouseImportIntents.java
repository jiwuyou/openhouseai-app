package com.termux.app.openhouse.files.importing;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.termux.shared.net.uri.UriUtils;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.net.URLConnection;

public final class OpenHouseImportIntents {

    private static final String FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider";
    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    private OpenHouseImportIntents() {
    }

    public static Intent createShareAiDescriptionIntent(OpenHouseImportedFile importedFile) {
        ArrayList<OpenHouseImportedFile> importedFiles = new ArrayList<>();
        importedFiles.add(importedFile);
        return createShareAiDescriptionIntent(importedFiles);
    }

    public static Intent createShareAiDescriptionIntent(List<OpenHouseImportedFile> importedFiles) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, buildAiDescriptionSubject(importedFiles));
        intent.putExtra(Intent.EXTRA_TEXT, buildAiDescription(importedFiles));
        return intent;
    }

    public static Intent createOpenExternallyIntent(Context context, OpenHouseImportedFile importedFile) {
        Uri uri = getFileProviderUri(context, importedFile);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, resolveMimeType(importedFile));
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    public static Intent createOpenExternallyIntent(Context context, List<OpenHouseImportedFile> importedFiles) {
        if (importedFiles == null || importedFiles.isEmpty()) {
            Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            intent.setType(DEFAULT_MIME_TYPE);
            return intent;
        }
        if (importedFiles.size() == 1) {
            return createOpenExternallyIntent(context, importedFiles.get(0));
        }

        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clipData = null;
        for (OpenHouseImportedFile importedFile : importedFiles) {
            Uri uri = getFileProviderUri(context, importedFile);
            uris.add(uri);
            if (clipData == null) {
                clipData = ClipData.newUri(context.getContentResolver(), importedFile.getDisplayName(), uri);
            } else {
                clipData.addItem(context.getContentResolver(), new ClipData.Item(uri));
            }
        }

        Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        intent.setType(resolveCommonMimeType(importedFiles));
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        if (clipData != null) {
            intent.setClipData(clipData);
        }
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

    public static String buildAiDescription(List<OpenHouseImportedFile> importedFiles) {
        if (importedFiles == null || importedFiles.isEmpty()) {
            return "";
        }
        if (importedFiles.size() == 1) {
            return importedFiles.get(0).buildAiDescription();
        }

        StringBuilder description = new StringBuilder();
        description.append("以下文件已导入 OpenHouse 文件中转站，共 ")
            .append(importedFiles.size())
            .append(" 个。");
        for (int i = 0; i < importedFiles.size(); i++) {
            OpenHouseImportedFile importedFile = importedFiles.get(i);
            description.append("\n\n")
                .append(i + 1)
                .append(". ")
                .append(importedFile.getDisplayName())
                .append("\n")
                .append(importedFile.buildAiDescription());
        }
        return description.toString();
    }

    private static String buildAiDescriptionSubject(List<OpenHouseImportedFile> importedFiles) {
        if (importedFiles == null || importedFiles.isEmpty()) {
            return "OpenHouse 文件说明";
        }
        if (importedFiles.size() == 1) {
            return "OpenHouse 文件说明: " + importedFiles.get(0).getDisplayName();
        }
        return "OpenHouse 文件说明: " + importedFiles.size() + " 个文件";
    }

    private static Uri getFileProviderUri(Context context, OpenHouseImportedFile importedFile) {
        File file = importedFile.getFile();
        return FileProvider.getUriForFile(
            context,
            context.getPackageName() + FILE_PROVIDER_AUTHORITY_SUFFIX,
            file
        );
    }

    private static String resolveCommonMimeType(List<OpenHouseImportedFile> importedFiles) {
        String commonMimeType = null;
        for (OpenHouseImportedFile importedFile : importedFiles) {
            String mimeType = resolveMimeType(importedFile);
            if (commonMimeType == null) {
                commonMimeType = mimeType;
            } else if (!commonMimeType.equals(mimeType)) {
                return "*/*";
            }
        }
        return commonMimeType == null ? DEFAULT_MIME_TYPE : commonMimeType;
    }
}
