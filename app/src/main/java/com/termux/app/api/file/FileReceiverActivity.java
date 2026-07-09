package com.termux.app.api.file;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
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
import com.termux.app.openhouse.files.importing.OpenHouseInboxGrouping;
import com.termux.app.openhouse.files.importing.OpenHouseImportIntents;
import com.termux.app.openhouse.files.importing.OpenHouseImportSource;
import com.termux.app.openhouse.files.importing.OpenHouseImportSources;
import com.termux.app.openhouse.files.importing.OpenHouseImportedFile;
import com.termux.app.openhouse.files.ui.OpenHouseFilesConfigStore;
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
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
    private static final ImportAction[] SINGLE_IMPORT_RESULT_ACTIONS = new ImportAction[]{
        new ImportAction(IMPORT_ACTION_SEND_TO_AI, "给 AI 描述"),
        new ImportAction(IMPORT_ACTION_COPY_DESCRIPTION, "复制说明"),
        new ImportAction(IMPORT_ACTION_OPEN_EXTERNALLY, "下载/用其他应用打开"),
        new ImportAction(IMPORT_ACTION_LEGACY_TERMUX_EDITOR, "旧 Termux 编辑"),
        new ImportAction(IMPORT_ACTION_LEGACY_TERMUX_RECEIVE_DIR, "旧 Termux 接收目录")
    };
    private static final ImportAction[] MULTIPLE_IMPORT_RESULT_ACTIONS = new ImportAction[]{
        new ImportAction(IMPORT_ACTION_SEND_TO_AI, "给 AI 描述"),
        new ImportAction(IMPORT_ACTION_COPY_DESCRIPTION, "复制说明"),
        new ImportAction(IMPORT_ACTION_OPEN_EXTERNALLY, "下载/用其他应用打开"),
        new ImportAction(IMPORT_ACTION_LEGACY_TERMUX_RECEIVE_DIR, "旧 Termux 接收目录")
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

        Logger.logVerbose(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent));

        importIntentInBackground(intent);
    }

    private void importIntentInBackground(final Intent intent) {
        new Thread(() -> {
            final ImportBatchResult result = importIntent(intent);
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    showImportBatchResultDialog(result);
                }
            });
        }, "OpenHouseFileImport").start();
    }

    ImportBatchResult importIntent(Intent intent) {
        if (intent == null) {
            ImportBatchResult result = new ImportBatchResult();
            result.addFailure("Android intent", "Intent not passed.");
            return result;
        }
        final String action = intent == null ? null : intent.getAction();

        ImportBatchResult result;
        if (Intent.ACTION_SEND.equals(action)) {
            result = importSendIntent(intent);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            result = importSendMultipleIntent(intent);
        } else {
            result = importViewIntent(intent);
        }
        return result;
    }

    void showErrorDialogAndQuit(String message) {
        mFinishOnDismissNameDialog = false;
        MessageDialogUtils.showMessage(this,
            API_TAG, message,
            null, (dialog, which) -> finish(),
            null, null,
            dialog -> finish());
    }

    ImportBatchResult importSendIntent(Intent intent) {
        ImportBatchResult result = new ImportBatchResult();
        String subject = getSubjectOrTitle(intent);
        List<Uri> streamUris = collectStreamUris(intent);
        if (!streamUris.isEmpty()) {
            importUrisIntoBatch(result, streamUris, subject, intent.getType());
            return result;
        }

        List<String> textPayloads = collectTextPayloads(intent);
        if (!textPayloads.isEmpty()) {
            importTextsIntoBatch(result, textPayloads, subject, intent.getType());
            return result;
        }

        result.addFailure("Android share sheet", "Send action without content - nothing to save.");
        return result;
    }

    ImportBatchResult importSendMultipleIntent(Intent intent) {
        ImportBatchResult result = new ImportBatchResult();
        List<Uri> streamUris = collectStreamUris(intent);
        if (!streamUris.isEmpty()) {
            importUrisIntoBatch(result, streamUris, getSubjectOrTitle(intent), intent.getType());
            return result;
        }

        List<String> textPayloads = collectTextPayloads(intent);
        if (!textPayloads.isEmpty()) {
            importTextsIntoBatch(result, textPayloads, getSubjectOrTitle(intent), intent.getType());
            return result;
        }

        result.addFailure("Android share sheet", "Send multiple action without content - nothing to save.");
        return result;
    }

    ImportBatchResult importViewIntent(Intent intent) {
        ImportBatchResult result = new ImportBatchResult();
        List<Uri> viewUris = collectViewUris(intent);
        if (viewUris.isEmpty()) {
            result.addFailure("Android open-with intent", "Data uri not passed.");
            return result;
        }
        importUrisIntoBatch(result, viewUris, getSubjectOrTitle(intent), intent.getType());
        return result;
    }

    void handleContentUri(@NonNull final Uri uri, String subjectFromIntent) {
        ImportBatchResult result = new ImportBatchResult();
        importUriIntoBatch(result, uri, subjectFromIntent, getIntent().getType());
        showImportBatchResultDialog(result);
    }

    void importSharedText(String sharedText, String subject, String mimeType) {
        ImportBatchResult result = new ImportBatchResult();
        importTextIntoBatch(result, sharedText, subject, mimeType);
        showImportBatchResultDialog(result);
    }

    void importStreamAndShowActions(InputStream in, OpenHouseImportSource source) {
        try {
            showImportResultDialog(importStream(in, source));
        } catch (IOException e) {
            showErrorDialogAndQuit("Error importing file into OpenHouse inbox:\n\n" + e.getMessage());
            Logger.logStackTraceWithMessage(LOG_TAG, "Error importing file into OpenHouse inbox", e);
        }
    }

    private List<Uri> collectStreamUris(Intent intent) {
        ArrayList<Uri> uris = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();

        Object streamExtra = intent.getExtras() == null ? null : intent.getExtras().get(Intent.EXTRA_STREAM);
        if (streamExtra instanceof Uri) {
            addUriIfNew(uris, seen, (Uri) streamExtra);
        } else if (streamExtra instanceof ArrayList<?>) {
            ArrayList<?> streamUris = (ArrayList<?>) streamExtra;
            for (Object item : streamUris) {
                if (item instanceof Uri) {
                    addUriIfNew(uris, seen, (Uri) item);
                }
            }
        }

        ClipData clipData = intent.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                addUriIfNew(uris, seen, clipData.getItemAt(i).getUri());
            }
        }

        return uris;
    }

    private List<Uri> collectViewUris(Intent intent) {
        ArrayList<Uri> uris = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        addUriIfNew(uris, seen, intent.getData());

        ClipData clipData = intent.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                addUriIfNew(uris, seen, clipData.getItemAt(i).getUri());
            }
        }

        return uris;
    }

    private void addUriIfNew(List<Uri> uris, LinkedHashSet<String> seen, Uri uri) {
        if (uri == null) {
            return;
        }
        String key = uri.toString();
        if (seen.add(key)) {
            uris.add(uri);
        }
    }

    private List<String> collectTextPayloads(Intent intent) {
        ArrayList<String> textPayloads = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();

        addTextIfNew(textPayloads, seen, intent.getStringExtra(Intent.EXTRA_TEXT));

        ClipData clipData = intent.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                CharSequence text = clipData.getItemAt(i).getText();
                if (text != null) {
                    addTextIfNew(textPayloads, seen, text.toString());
                }
            }
        }

        return textPayloads;
    }

    private void addTextIfNew(List<String> textPayloads, LinkedHashSet<String> seen, String text) {
        if (DataUtils.isNullOrEmpty(text)) {
            return;
        }
        if (seen.add(text)) {
            textPayloads.add(text);
        }
    }

    private String getSubjectOrTitle(Intent intent) {
        String subject = IntentUtils.getStringExtraIfSet(intent, Intent.EXTRA_SUBJECT, null);
        if (subject != null) {
            return subject;
        }
        return IntentUtils.getStringExtraIfSet(intent, Intent.EXTRA_TITLE, null);
    }

    private void importUrisIntoBatch(ImportBatchResult result, List<Uri> uris, String subjectFromIntent, String intentMimeType) {
        for (Uri uri : uris) {
            importUriIntoBatch(result, uri, subjectFromIntent, intentMimeType);
        }
    }

    private void importUriIntoBatch(ImportBatchResult result, Uri uri, String subjectFromIntent, String intentMimeType) {
        try {
            result.addImported(importUri(uri, subjectFromIntent, intentMimeType));
        } catch (Exception e) {
            String label = uri == null ? "Android uri" : uri.toString();
            result.addFailure(label, buildFailureMessage(e));
            Logger.logStackTraceWithMessage(LOG_TAG, "Importing uri failed: " + label, e);
        }
    }

    private OpenHouseImportedFile importUri(@NonNull final Uri uri, String subjectFromIntent, String intentMimeType) throws IOException {
        Logger.logVerbose(LOG_TAG, "uri: \"" + uri + "\", path: \"" + uri.getPath() + "\", fragment: \"" + uri.getFragment() + "\"");

        String scheme = uri.getScheme();
        if (UriScheme.SCHEME_CONTENT.equals(scheme)) {
            return importContentUri(uri, subjectFromIntent, intentMimeType);
        }
        if (UriScheme.SCHEME_FILE.equals(scheme)) {
            return importFileUri(uri, intentMimeType);
        }
        throw new IOException("Unsupported URI scheme: " + scheme);
    }

    private OpenHouseImportedFile importContentUri(@NonNull final Uri uri, String subjectFromIntent, String intentMimeType) throws IOException {
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
        if (mimeType == null) mimeType = intentMimeType;
        OpenHouseImportSource source = OpenHouseImportSource.builder()
            .setSuggestedFileName(attachmentFileName)
            .setMimeType(mimeType)
            .setSizeBytes(attachmentSize)
            .setAndroidUri(uri.toString())
            .setAndroidDisplayLocation(OpenHouseImportSources.buildAndroidDisplayLocation(uri, attachmentFileName))
            .build();
        return importStream(in, source);
    }

    private OpenHouseImportedFile importFileUri(@NonNull final Uri uri, String intentMimeType) throws IOException {
        // Get full path including fragment (anything after last "#")
        String path = UriUtils.getUriFilePathWithFragment(uri);
        if (DataUtils.isNullOrEmpty(path)) {
            throw new IOException("File path from data uri is null, empty or invalid.");
        }

        File file = new File(path);
        OpenHouseImportSource source = OpenHouseImportSource.builder()
            .setSuggestedFileName(file.getName())
            .setMimeType(intentMimeType)
            .setSizeBytes(file.length())
            .setAndroidUri(uri.toString())
            .setAndroidDisplayLocation(file.getAbsolutePath())
            .build();
        return importStream(new FileInputStream(file), source);
    }

    private void importTextsIntoBatch(ImportBatchResult result, List<String> sharedTexts, String subject, String mimeType) {
        for (String sharedText : sharedTexts) {
            importTextIntoBatch(result, sharedText, subject, mimeType);
        }
    }

    private void importTextIntoBatch(ImportBatchResult result, String sharedText, String subject, String mimeType) {
        try {
            result.addImported(importStream(
                new ByteArrayInputStream(OpenHouseImportSources.sharedTextBytes(sharedText)),
                OpenHouseImportSources.forSharedText(sharedText, subject, mimeType)
            ));
        } catch (IOException e) {
            result.addFailure("Android share sheet text", buildFailureMessage(e));
            Logger.logStackTraceWithMessage(LOG_TAG, "Importing shared text failed", e);
        }
    }

    private OpenHouseImportedFile importStream(InputStream in, OpenHouseImportSource source) throws IOException {
        return new OpenHouseFileImportManager(createImportWorkspacePaths(), getInboxGrouping()).importStream(in, source);
    }

    OpenHouseWorkspacePaths createImportWorkspacePaths() {
        return OpenHouseWorkspacePaths.forTermuxHome(TermuxConstants.TERMUX_HOME_DIR_PATH);
    }

    OpenHouseInboxGrouping getInboxGrouping() {
        return new OpenHouseFilesConfigStore(this).getInboxGrouping();
    }

    private static String buildFailureMessage(Exception e) {
        if (e == null) {
            return "Unknown error";
        }
        String message = e.getMessage();
        if (!DataUtils.isNullOrEmpty(message)) {
            return message;
        }
        return e.getClass().getSimpleName();
    }

    /*
     * Legacy entry points below intentionally keep the old Termux receiver behavior
     * as explicit actions after the file has already been copied to OpenHouse inbox.
     */

    void showImportResultDialog(OpenHouseImportedFile importedFile) {
        ImportBatchResult result = new ImportBatchResult();
        result.addImported(importedFile);
        showImportBatchResultDialog(result);
    }

    void showImportBatchResultDialog(ImportBatchResult result) {
        if (result == null || result.getImportedFiles().isEmpty()) {
            String message = "没有文件导入文件中转站。";
            if (result != null && result.hasFailures()) {
                message += "\n\n" + result.buildFailureSummary();
            }
            showErrorDialogAndQuit(message);
            return;
        }

        ImportAction[] actions = result.getImportedFiles().size() == 1
            ? SINGLE_IMPORT_RESULT_ACTIONS
            : MULTIPLE_IMPORT_RESULT_ACTIONS;
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("已导入文件中转站")
            .setMessage(result.buildDialogMessage())
            .setItems(toLabels(actions), (dialogInterface, which) -> handleImportResultAction(result.getImportedFiles(), actions[which].id))
            .setNegativeButton(android.R.string.cancel, (dialogInterface, which) -> finish())
            .setOnCancelListener(dialogInterface -> finish())
            .create();
        dialog.show();
    }

    private CharSequence[] toLabels(ImportAction[] actions) {
        CharSequence[] labels = new CharSequence[actions.length];
        for (int i = 0; i < actions.length; i++) {
            labels[i] = actions[i].label;
        }
        return labels;
    }

    void handleImportResultAction(List<OpenHouseImportedFile> importedFiles, int action) {
        if (importedFiles == null || importedFiles.isEmpty()) {
            finish();
            return;
        }

        if (action == IMPORT_ACTION_SEND_TO_AI) {
            openIntentAndFinish(
                Intent.createChooser(OpenHouseImportIntents.createShareAiDescriptionIntent(importedFiles), "给 AI 描述"),
                "No app is available to receive the AI file description."
            );
        } else if (action == IMPORT_ACTION_COPY_DESCRIPTION) {
            ShareUtils.copyTextToClipboard(
                this,
                "OpenHouse AI file description",
                OpenHouseImportIntents.buildAiDescription(importedFiles),
                "已复制给 AI 的文件说明"
            );
            finish();
        } else if (action == IMPORT_ACTION_OPEN_EXTERNALLY) {
            openIntentAndFinish(
                Intent.createChooser(OpenHouseImportIntents.createOpenExternallyIntent(this, importedFiles), "用其他应用打开"),
                "No app is available to open this file."
            );
        } else if (action == IMPORT_ACTION_LEGACY_TERMUX_EDITOR) {
            openLegacyTermuxEditor(importedFiles.get(0).getFile());
        } else if (action == IMPORT_ACTION_LEGACY_TERMUX_RECEIVE_DIR) {
            openLegacyTermuxReceiveDir();
        } else {
            finish();
        }
    }

    private static final class ImportAction {
        final int id;
        final CharSequence label;

        ImportAction(int id, CharSequence label) {
            this.id = id;
            this.label = label;
        }
    }

    static final class ImportBatchResult {
        private static final int MAX_DIALOG_FILES = 8;
        private static final int MAX_DIALOG_FAILURES = 5;

        private final ArrayList<OpenHouseImportedFile> importedFiles = new ArrayList<>();
        private final ArrayList<ImportFailure> failures = new ArrayList<>();

        void addImported(OpenHouseImportedFile importedFile) {
            if (importedFile != null) {
                importedFiles.add(importedFile);
            }
        }

        void addFailure(String label, String message) {
            failures.add(new ImportFailure(label, message));
        }

        List<OpenHouseImportedFile> getImportedFiles() {
            return importedFiles;
        }

        boolean hasFailures() {
            return !failures.isEmpty();
        }

        String buildDialogMessage() {
            StringBuilder message = new StringBuilder();
            if (importedFiles.size() == 1) {
                appendImportedFileSummary(message, importedFiles.get(0));
            } else {
                message.append("成功导入: ").append(importedFiles.size()).append(" 个文件");
                for (int i = 0; i < importedFiles.size() && i < MAX_DIALOG_FILES; i++) {
                    message.append("\n\n");
                    appendImportedFileSummary(message, importedFiles.get(i));
                }
                if (importedFiles.size() > MAX_DIALOG_FILES) {
                    message.append("\n\n另有 ")
                        .append(importedFiles.size() - MAX_DIALOG_FILES)
                        .append(" 个文件已导入。");
                }
            }

            if (hasFailures()) {
                message.append("\n\n").append(buildFailureSummary());
            }
            return message.toString();
        }

        String buildFailureSummary() {
            StringBuilder message = new StringBuilder();
            message.append("失败: ").append(failures.size()).append(" 项");
            for (int i = 0; i < failures.size() && i < MAX_DIALOG_FAILURES; i++) {
                ImportFailure failure = failures.get(i);
                message.append("\n- ").append(failure.label).append(": ").append(failure.message);
            }
            if (failures.size() > MAX_DIALOG_FAILURES) {
                message.append("\n- 另有 ")
                    .append(failures.size() - MAX_DIALOG_FAILURES)
                    .append(" 项失败。");
            }
            return message.toString();
        }

        private static void appendImportedFileSummary(StringBuilder message, OpenHouseImportedFile importedFile) {
            message.append("文件: ").append(importedFile.getDisplayName())
                .append("\nOpenHouse workspace: ").append(importedFile.getWorkspacePath())
                .append("\nTermux: ").append(importedFile.getTermuxPath())
                .append("\nUbuntu: ").append(importedFile.getUbuntuPath());
        }
    }

    private static final class ImportFailure {
        final String label;
        final String message;

        ImportFailure(String label, String message) {
            this.label = DataUtils.isNullOrEmpty(label) ? "Android payload" : label;
            this.message = DataUtils.isNullOrEmpty(message) ? "Unknown error" : message;
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
            OpenHouseImportSource source = OpenHouseImportSource.builder()
                .setSuggestedFileName(OpenHouseFileNameSanitizer.sanitize(attachmentFileName))
                .setAndroidDisplayLocation("Termux file receiver")
                .build();
            return importStream(in, source).getFile();
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
