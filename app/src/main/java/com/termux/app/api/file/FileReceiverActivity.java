package com.termux.app.api.file;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Patterns;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.app.openhouse.files.core.OpenHouseFileNameSanitizer;
import com.termux.app.openhouse.files.core.OpenHouseWorkspacePaths;
import com.termux.app.openhouse.files.importing.OpenHouseFileImportManager;
import com.termux.app.openhouse.files.importing.OpenHouseImportIntents;
import com.termux.app.openhouse.files.importing.OpenHouseImportSource;
import com.termux.app.openhouse.files.importing.OpenHouseImportSources;
import com.termux.app.openhouse.files.importing.OpenHouseImportedFile;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.net.uri.UriUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.net.uri.UriScheme;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;
import com.termux.app.TermuxService;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Pattern;

public class FileReceiverActivity extends AppCompatActivity {

    static final String TERMUX_RECEIVEDIR = TermuxConstants.TERMUX_HOME_DIR_PATH + "/openhouse/workspace/inbox";
    static final String EDITOR_PROGRAM = TermuxConstants.TERMUX_HOME_DIR_PATH + "/bin/termux-file-editor";
    static final String URL_OPENER_PROGRAM = TermuxConstants.TERMUX_HOME_DIR_PATH + "/bin/termux-url-opener";
    private static final int IMPORT_ACTION_SEND_TO_AI = 0;
    private static final int IMPORT_ACTION_COPY_DESCRIPTION = 1;
    private static final int IMPORT_ACTION_OPEN_EXTERNALLY = 2;
    private static final int IMPORT_ACTION_LEGACY_TERMUX_EDITOR = 3;
    private static final int IMPORT_ACTION_LEGACY_TERMUX_RECEIVE_DIR = 4;
    private static final CharSequence[] IMPORT_RESULT_ACTIONS = new CharSequence[]{
        "给 AI 描述",
        "复制说明",
        "下载/用其他应用打开",
        "旧 Termux 编辑",
        "旧 Termux 接收目录"
    };

    /**
     * If the activity should be finished when the name input dialog is dismissed. This is disabled
     * before showing an error dialog, since the act of showing the error dialog will cause the
     * name input dialog to be implicitly dismissed, and we do not want to finish the activity directly
     * when showing the error dialog.
     */
    boolean mFinishOnDismissNameDialog = true;
    private boolean mHandledIntent;

    private static final String API_TAG = TermuxConstants.TERMUX_APP_NAME + "FileReceiver";

    private static final String LOG_TAG = "FileReceiverActivity";

    static boolean isSharedTextAnUrl(String sharedText) {
        if (sharedText == null || sharedText.isEmpty()) return false;

        return Patterns.WEB_URL.matcher(sharedText).matches()
            || Pattern.matches("magnet:\\?xt=urn:btih:.*?", sharedText);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mHandledIntent) {
            return;
        }
        mHandledIntent = true;

        final Intent intent = getIntent();
        final String action = intent.getAction();
        final String type = intent.getType();
        final String scheme = intent.getScheme();

        Logger.logVerbose(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent));

        final String sharedTitle = IntentUtils.getStringExtraIfSet(intent, Intent.EXTRA_TITLE, null);

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            final String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            final Uri sharedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);

            if (sharedUri != null) {
                handleContentUri(sharedUri, sharedTitle);
            } else if (sharedText != null) {
                if (isSharedTextAnUrl(sharedText)) {
                    handleUrlAndFinish(sharedText);
                } else {
                    String subject = IntentUtils.getStringExtraIfSet(intent, Intent.EXTRA_SUBJECT, null);
                    if (subject == null) subject = sharedTitle;
                    importSharedText(sharedText, subject, type);
                }
            } else {
                showErrorDialogAndQuit("Send action without content - nothing to save.");
            }
        } else {
            Uri dataUri = intent.getData();

            if (dataUri == null) {
                showErrorDialogAndQuit("Data uri not passed.");
                return;
            }

            if (UriScheme.SCHEME_CONTENT.equals(scheme)) {
                handleContentUri(dataUri, sharedTitle);
            } else if (UriScheme.SCHEME_FILE.equals(scheme)) {
                Logger.logVerbose(LOG_TAG, "uri: \"" + dataUri + "\", path: \"" + dataUri.getPath() + "\", fragment: \"" + dataUri.getFragment() + "\"");

                // Get full path including fragment (anything after last "#")
                String path = UriUtils.getUriFilePathWithFragment(dataUri);
                if (DataUtils.isNullOrEmpty(path)) {
                    showErrorDialogAndQuit("File path from data uri is null, empty or invalid.");
                    return;
                }

                File file = new File(path);
                try {
                    FileInputStream in = new FileInputStream(file);
                    OpenHouseImportSource source = OpenHouseImportSource.builder()
                        .setSuggestedFileName(file.getName())
                        .setMimeType(type)
                        .setSizeBytes(file.length())
                        .setAndroidUri(dataUri.toString())
                        .setAndroidDisplayLocation(file.getAbsolutePath())
                        .build();
                    importStreamAndShowActions(in, source);
                } catch (FileNotFoundException e) {
                    showErrorDialogAndQuit("Cannot open file: " + e.getMessage() + ".");
                }
            } else {
                showErrorDialogAndQuit("Unable to receive any file or URL.");
            }
        }
    }

    void showErrorDialogAndQuit(String message) {
        mFinishOnDismissNameDialog = false;
        MessageDialogUtils.showMessage(this,
            API_TAG, message,
            null, (dialog, which) -> finish(),
            null, null,
            dialog -> finish());
    }

    void handleContentUri(@NonNull final Uri uri, String subjectFromIntent) {
        try {
            Logger.logVerbose(LOG_TAG, "uri: \"" + uri + "\", path: \"" + uri.getPath() + "\", fragment: \"" + uri.getFragment() + "\"");

            String attachmentFileName = null;
            long attachmentSize = -1;

            String[] projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            try (Cursor c = getContentResolver().query(uri, projection, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    final int fileNameColumnId = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (fileNameColumnId >= 0) attachmentFileName = c.getString(fileNameColumnId);
                    final int sizeColumnId = c.getColumnIndex(OpenableColumns.SIZE);
                    if (sizeColumnId >= 0 && !c.isNull(sizeColumnId)) attachmentSize = c.getLong(sizeColumnId);
                }
            }

            attachmentFileName = OpenHouseImportSources.chooseContentFileName(
                attachmentFileName,
                subjectFromIntent,
                UriUtils.getUriFileBasename(uri, true)
            );

            InputStream in = getContentResolver().openInputStream(uri);
            String mimeType = getContentResolver().getType(uri);
            if (mimeType == null) mimeType = getIntent().getType();
            OpenHouseImportSource source = OpenHouseImportSource.builder()
                .setSuggestedFileName(attachmentFileName)
                .setMimeType(mimeType)
                .setSizeBytes(attachmentSize)
                .setAndroidUri(uri.toString())
                .setAndroidDisplayLocation(OpenHouseImportSources.buildAndroidDisplayLocation(uri, attachmentFileName))
                .build();
            importStreamAndShowActions(in, source);
        } catch (Exception e) {
            showErrorDialogAndQuit("Unable to handle shared content:\n\n" + e.getMessage());
            Logger.logStackTraceWithMessage(LOG_TAG, "handleContentUri(uri=" + uri + ") failed", e);
        }
    }

    void importSharedText(String sharedText, String subject, String mimeType) {
        importStreamAndShowActions(
            new ByteArrayInputStream(OpenHouseImportSources.sharedTextBytes(sharedText)),
            OpenHouseImportSources.forSharedText(sharedText, subject, mimeType)
        );
    }

    void importStreamAndShowActions(InputStream in, OpenHouseImportSource source) {
        try {
            OpenHouseWorkspacePaths paths = OpenHouseWorkspacePaths.forTermuxHome(TermuxConstants.TERMUX_HOME_DIR_PATH);
            OpenHouseImportedFile importedFile = new OpenHouseFileImportManager(paths).importStream(in, source);
            showImportResultDialog(importedFile);
        } catch (IOException e) {
            showErrorDialogAndQuit("Error importing file into OpenHouse inbox:\n\n" + e.getMessage());
            Logger.logStackTraceWithMessage(LOG_TAG, "Error importing file into OpenHouse inbox", e);
        }
    }

    void showImportResultDialog(OpenHouseImportedFile importedFile) {
        String message = "文件: " + importedFile.getDisplayName()
            + "\nOpenHouse workspace: " + importedFile.getWorkspacePath()
            + "\nTermux: " + importedFile.getTermuxPath()
            + "\nUbuntu: " + importedFile.getUbuntuPath();

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("已导入 OpenHouse inbox")
            .setMessage(message)
            .setItems(IMPORT_RESULT_ACTIONS, (dialogInterface, which) -> handleImportResultAction(importedFile, which))
            .setNegativeButton(android.R.string.cancel, (dialogInterface, which) -> finish())
            .setOnCancelListener(dialogInterface -> finish())
            .create();
        dialog.show();
    }

    void handleImportResultAction(OpenHouseImportedFile importedFile, int action) {
        if (action == IMPORT_ACTION_SEND_TO_AI) {
            openIntentAndFinish(
                Intent.createChooser(OpenHouseImportIntents.createShareAiDescriptionIntent(importedFile), "给 AI 描述"),
                "No app is available to receive the AI file description."
            );
        } else if (action == IMPORT_ACTION_COPY_DESCRIPTION) {
            ShareUtils.copyTextToClipboard(
                this,
                "OpenHouse AI file description",
                importedFile.buildAiDescription(),
                "已复制给 AI 的文件说明"
            );
            finish();
        } else if (action == IMPORT_ACTION_OPEN_EXTERNALLY) {
            openIntentAndFinish(
                Intent.createChooser(OpenHouseImportIntents.createOpenExternallyIntent(this, importedFile), "用其他应用打开"),
                "No app is available to open this file."
            );
        } else if (action == IMPORT_ACTION_LEGACY_TERMUX_EDITOR) {
            openLegacyTermuxEditor(importedFile.getFile());
        } else if (action == IMPORT_ACTION_LEGACY_TERMUX_RECEIVE_DIR) {
            openLegacyTermuxReceiveDir();
        } else {
            finish();
        }
    }

    void openIntentAndFinish(Intent intent, String errorMessage) {
        try {
            startActivity(intent);
            finish();
        } catch (ActivityNotFoundException e) {
            showErrorDialogAndQuit(errorMessage);
        }
    }

    String buildAndroidDisplayLocation(Uri uri, String attachmentFileName) {
        return OpenHouseImportSources.buildAndroidDisplayLocation(uri, attachmentFileName);
    }

    void promptNameAndSave(final InputStream in, final String attachmentFileName) {
        TextInputDialogUtils.textInput(this, R.string.title_file_received, attachmentFileName,
            R.string.action_file_received_edit, text -> {
                File outFile = saveStreamWithName(in, text);
                if (outFile == null) return;
                openLegacyTermuxEditor(outFile);
            },
            R.string.action_file_received_open_directory, text -> {
                if (saveStreamWithName(in, text) == null) return;
                openLegacyTermuxReceiveDir();
            },
            android.R.string.cancel, text -> finish(), dialog -> {
                if (mFinishOnDismissNameDialog) finish();
            });
    }

    public File saveStreamWithName(InputStream in, String attachmentFileName) {
        if (DataUtils.isNullOrEmpty(attachmentFileName)) {
            showErrorDialogAndQuit("File name cannot be null or empty");
            return null;
        }

        try {
            OpenHouseWorkspacePaths paths = OpenHouseWorkspacePaths.forTermuxHome(TermuxConstants.TERMUX_HOME_DIR_PATH);
            OpenHouseImportSource source = OpenHouseImportSource.builder()
                .setSuggestedFileName(OpenHouseFileNameSanitizer.sanitize(attachmentFileName))
                .setAndroidDisplayLocation("Termux file receiver")
                .build();
            return new OpenHouseFileImportManager(paths).importStream(in, source).getFile();
        } catch (IOException e) {
            showErrorDialogAndQuit("Error saving file:\n\n" + e);
            Logger.logStackTraceWithMessage(LOG_TAG, "Error saving file", e);
            return null;
        }
    }

    void openLegacyTermuxEditor(File file) {
        final File editorProgramFile = new File(EDITOR_PROGRAM);
        if (!editorProgramFile.isFile()) {
            showErrorDialogAndQuit("The following file does not exist:\n$HOME/bin/termux-file-editor\n\n"
                + "Create this file as a script or a symlink - it will be called with the received file as only argument.");
            return;
        }

        // Do this for the user if necessary:
        //noinspection ResultOfMethodCallIgnored
        editorProgramFile.setExecutable(true);

        startService(OpenHouseImportIntents.createLegacyEditorServiceIntent(
            getPackageName(),
            TermuxService.class,
            EDITOR_PROGRAM,
            file
        ));
        finish();
    }

    void openLegacyTermuxReceiveDir() {
        startService(OpenHouseImportIntents.createLegacyOpenReceiveDirServiceIntent(
            getPackageName(),
            TermuxService.class,
            TERMUX_RECEIVEDIR
        ));
        finish();
    }

    void handleUrlAndFinish(final String url) {
        final File urlOpenerProgramFile = new File(URL_OPENER_PROGRAM);
        if (!urlOpenerProgramFile.isFile()) {
            showErrorDialogAndQuit("The following file does not exist:\n$HOME/bin/termux-url-opener\n\n"
                + "Create this file as a script or a symlink - it will be called with the shared URL as the first argument.");
            return;
        }

        // Do this for the user if necessary:
        //noinspection ResultOfMethodCallIgnored
        urlOpenerProgramFile.setExecutable(true);

        final Uri urlOpenerProgramUri = UriUtils.getFileUri(URL_OPENER_PROGRAM);

        Intent executeIntent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, urlOpenerProgramUri);
        executeIntent.setClass(FileReceiverActivity.this, TermuxService.class);
        executeIntent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, new String[]{url});
        startService(executeIntent);
        finish();
    }

    /**
     * Update {@link TERMUX_APP#FILE_SHARE_RECEIVER_ACTIVITY_CLASS_NAME} component state depending on
     * {@link TermuxPropertyConstants#KEY_DISABLE_FILE_SHARE_RECEIVER} value and
     * {@link TERMUX_APP#FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME} component state depending on
     * {@link TermuxPropertyConstants#KEY_DISABLE_FILE_VIEW_RECEIVER} value.
     */
    public static void updateFileReceiverActivityComponentsState(@NonNull Context context) {
        new Thread() {
            @Override
            public void run() {
                TermuxAppSharedProperties properties = TermuxAppSharedProperties.getProperties();

                String errmsg;
                boolean state;

                state = !properties.isFileShareReceiverDisabled();
                Logger.logVerbose(LOG_TAG, "Setting " + TERMUX_APP.FILE_SHARE_RECEIVER_ACTIVITY_CLASS_NAME + " component state to " + state);
                errmsg = PackageUtils.setComponentState(context,TermuxConstants.TERMUX_PACKAGE_NAME,
                    TERMUX_APP.FILE_SHARE_RECEIVER_ACTIVITY_CLASS_NAME,
                    state, null, false, false);
                if (errmsg != null)
                    Logger.logError(LOG_TAG, errmsg);

                state = !properties.isFileViewReceiverDisabled();
                Logger.logVerbose(LOG_TAG, "Setting " + TERMUX_APP.FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME + " component state to " + state);
                errmsg = PackageUtils.setComponentState(context,TermuxConstants.TERMUX_PACKAGE_NAME,
                    TERMUX_APP.FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME,
                    state, null, false, false);
                if (errmsg != null)
                    Logger.logError(LOG_TAG, errmsg);

            }
        }.start();
    }

}
