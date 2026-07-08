package com.termux.app.openhouse.editor;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.KeyListener;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.openhouse.files.core.OpenHouseWorkspacePaths;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.noties.markwon.Markwon;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.linkify.LinkifyPlugin;

public class OpenHouseEditorActivity extends AppCompatActivity {

    public static final long MAX_EDITABLE_BYTES = 1024L * 1024L;

    private static final int REQUEST_OPEN_DOCUMENT = 0x4F01;
    private static final int REQUEST_EXPORT_DOCUMENT = 0x4F02;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Markwon markwon;
    private TextView titleView;
    private TextView metaView;
    private TextView statusView;
    private EditText editorView;
    private TextView previewView;
    private ScrollView previewScrollView;
    private Button saveButton;
    private Button exportButton;
    private Button previewButton;
    private Button outlineButton;
    private KeyListener editorKeyListener;

    private OpenHouseEditorDocument document;
    private OpenHouseEditorLoadResult loadResult;
    private boolean loadingText;
    private boolean dirty;
    private boolean previewMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        markwon = Markwon.builder(this)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .build();
        buildContentView();
        loadDocument(parseDocumentFromIntent(getIntent()));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        loadDocument(parseDocumentFromIntent(intent));
    }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        takePersistablePermission(data, uri);
        if (requestCode == REQUEST_OPEN_DOCUMENT) {
            loadDocument(parseDocumentFromUri(uri, data.getType()));
        } else if (requestCode == REQUEST_EXPORT_DOCUMENT) {
            exportCurrentText(uri);
        }
    }

    private void buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(R.color.surface));
        setContentView(root, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(14), dp(12), dp(14), dp(10));
        header.setBackgroundColor(color(R.color.panel));
        root.addView(header, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        titleView = new TextView(this);
        titleView.setTextColor(color(R.color.textPrimary));
        titleView.setTextSize(20);
        titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        titleView.setSingleLine(false);
        header.addView(titleView, fullWidthParams());

        metaView = new TextView(this);
        metaView.setTextColor(color(R.color.textSecondary));
        metaView.setTextSize(12);
        metaView.setSingleLine(false);
        header.addView(metaView, topMarginParams(4));

        statusView = new TextView(this);
        statusView.setTextColor(color(R.color.textSecondary));
        statusView.setTextSize(12);
        statusView.setSingleLine(false);
        header.addView(statusView, topMarginParams(6));

        addActionRows(header);

        FrameLayout contentFrame = new FrameLayout(this);
        root.addView(contentFrame, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f));

        editorView = new EditText(this);
        editorView.setGravity(Gravity.START | Gravity.TOP);
        editorView.setTextSize(14);
        editorView.setTypeface(Typeface.MONOSPACE);
        editorView.setTextColor(color(R.color.textPrimary));
        editorView.setBackgroundColor(color(R.color.panel));
        editorView.setPadding(dp(12), dp(12), dp(12), dp(12));
        editorView.setSingleLine(false);
        editorView.setHorizontallyScrolling(true);
        editorView.setInputType(InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editorView.setTextIsSelectable(true);
        editorKeyListener = editorView.getKeyListener();
        editorView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!loadingText) {
                    dirty = true;
                    updateButtonsAndStatus();
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        contentFrame.addView(editorView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        previewScrollView = new ScrollView(this);
        previewScrollView.setFillViewport(true);
        previewScrollView.setVisibility(View.GONE);
        previewView = new TextView(this);
        previewView.setTextSize(15);
        previewView.setTextColor(color(R.color.textPrimary));
        previewView.setPadding(dp(14), dp(14), dp(14), dp(24));
        previewView.setTextIsSelectable(true);
        previewScrollView.addView(previewView, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT,
            ScrollView.LayoutParams.WRAP_CONTENT));
        contentFrame.addView(previewScrollView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void addActionRows(LinearLayout header) {
        LinearLayout firstRow = actionRow();
        firstRow.addView(actionButton(getString(R.string.openhouse_editor_open), v -> openSystemFilePicker()));
        saveButton = actionButton(getString(R.string.openhouse_editor_save), v -> saveCurrentText());
        firstRow.addView(saveButton);
        exportButton = actionButton(getString(R.string.openhouse_editor_export), v -> openExportPicker());
        firstRow.addView(exportButton);
        header.addView(scrollableRow(firstRow), topMarginParams(10));

        LinearLayout secondRow = actionRow();
        previewButton = actionButton(getString(R.string.openhouse_editor_preview), v -> togglePreview());
        secondRow.addView(previewButton);
        outlineButton = actionButton(getString(R.string.openhouse_editor_outline), v -> showOutlineDialog());
        secondRow.addView(outlineButton);
        secondRow.addView(actionButton(getString(R.string.openhouse_editor_copy_ai), v -> copyAiDescription()));
        secondRow.addView(actionButton(getString(R.string.openhouse_editor_share_ai), v -> shareAiDescription()));
        header.addView(scrollableRow(secondRow), topMarginParams(6));
    }

    private OpenHouseEditorDocument parseDocumentFromIntent(Intent intent) {
        if (intent == null) {
            return newDraftDocument();
        }

        String explicitPath = trim(intent.getStringExtra(OpenHouseEditorContract.EXTRA_FILE_PATH));
        Uri uri = parseUri(trim(intent.getStringExtra(OpenHouseEditorContract.EXTRA_FILE_URI)));
        if (uri == null) {
            uri = intent.getData();
        }

        File localFile = null;
        if (!explicitPath.isEmpty()) {
            localFile = new File(explicitPath).getAbsoluteFile();
        } else if (uri != null && "file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            localFile = new File(uri.getPath()).getAbsoluteFile();
            uri = null;
        }

        ContentMetadata contentMetadata = uri == null ? ContentMetadata.empty() : queryContentMetadata(uri);
        long sizeBytes = getLongExtra(intent, OpenHouseEditorContract.EXTRA_SIZE_BYTES, -1L);
        if (sizeBytes < 0 && localFile != null && localFile.isFile()) {
            sizeBytes = localFile.length();
        }
        if (sizeBytes < 0) {
            sizeBytes = contentMetadata.sizeBytes;
        }

        String displayName = firstNonBlank(
            intent.getStringExtra(OpenHouseEditorContract.EXTRA_DISPLAY_NAME),
            contentMetadata.displayName,
            localFile == null ? null : localFile.getName(),
            uri == null ? null : uri.getLastPathSegment(),
            "untitled.md");
        String mimeType = firstNonBlank(
            intent.getStringExtra(OpenHouseEditorContract.EXTRA_MIME_TYPE),
            intent.getType(),
            contentMetadata.mimeType,
            OpenHouseEditorTextTypes.guessMimeType(displayName));
        String nativeLocation = firstNonBlank(
            intent.getStringExtra(OpenHouseEditorContract.EXTRA_NATIVE_LOCATION),
            localFile == null ? null : OpenHouseWorkspacePaths.normalize(localFile.getAbsolutePath()),
            uri == null ? null : uri.toString());

        OpenHouseEditorDocument document = OpenHouseEditorDocument.builder()
            .setLocalFile(localFile)
            .setUri(uri)
            .setAndroidUri(uri == null ? null : uri.toString())
            .setDisplayName(displayName)
            .setMimeType(mimeType)
            .setSizeBytes(sizeBytes)
            .setNativeLocation(nativeLocation)
            .setAndroidDisplayLocation(firstNonBlank(
                intent.getStringExtra(OpenHouseEditorContract.EXTRA_ANDROID_DISPLAY_LOCATION),
                buildAndroidDisplayLocation(uri, displayName)))
            .setTermuxPath(intent.getStringExtra(OpenHouseEditorContract.EXTRA_TERMUX_PATH))
            .setUbuntuPath(intent.getStringExtra(OpenHouseEditorContract.EXTRA_UBUNTU_PATH))
            .setWorkspacePath(intent.getStringExtra(OpenHouseEditorContract.EXTRA_WORKSPACE_PATH))
            .setSourceSpaceId(intent.getStringExtra(OpenHouseEditorContract.EXTRA_SOURCE_SPACE_ID))
            .setSourceFileId(intent.getStringExtra(OpenHouseEditorContract.EXTRA_SOURCE_FILE_ID))
            .setReadOnlyRequested(intent.getBooleanExtra(OpenHouseEditorContract.EXTRA_READ_ONLY, false))
            .setRepositoryExport(intent.getBooleanExtra(OpenHouseEditorContract.EXTRA_REPOSITORY_EXPORT, false))
            .build();
        return enrichWithWorkspacePaths(document);
    }

    private OpenHouseEditorDocument parseDocumentFromUri(Uri uri, String mimeType) {
        ContentMetadata metadata = queryContentMetadata(uri);
        String displayName = firstNonBlank(metadata.displayName, uri.getLastPathSegment(), "untitled.md");
        return OpenHouseEditorDocument.builder()
            .setUri(uri)
            .setAndroidUri(uri == null ? null : uri.toString())
            .setDisplayName(displayName)
            .setMimeType(firstNonBlank(mimeType, metadata.mimeType, OpenHouseEditorTextTypes.guessMimeType(displayName)))
            .setSizeBytes(metadata.sizeBytes)
            .setAndroidDisplayLocation(buildAndroidDisplayLocation(uri, displayName))
            .setNativeLocation(uri.toString())
            .build();
    }

    private OpenHouseEditorDocument newDraftDocument() {
        return OpenHouseEditorDocument.builder()
            .setDisplayName("untitled.md")
            .setMimeType("text/markdown")
            .setSizeBytes(0L)
            .build();
    }

    private OpenHouseEditorDocument enrichWithWorkspacePaths(OpenHouseEditorDocument source) {
        if (source == null || source.getLocalFile() == null) {
            return source;
        }
        OpenHouseEditorDocument.Builder builder = source.buildUpon();
        File file = source.getLocalFile().getAbsoluteFile();
        if (trim(source.getTermuxPath()).isEmpty()) {
            builder.setTermuxPath(OpenHouseWorkspacePaths.normalize(file.getAbsolutePath()));
        }
        OpenHouseWorkspacePaths paths = OpenHouseWorkspacePaths.forTermuxHome(TermuxConstants.TERMUX_HOME_DIR_PATH);
        String relativePath = paths.getWorkspaceRelativePath(file);
        if (relativePath != null) {
            if (trim(source.getWorkspacePath()).isEmpty()) {
                builder.setWorkspacePath(paths.getOpenHouseWorkspacePath(file));
            }
            if (trim(source.getUbuntuPath()).isEmpty()) {
                builder.setUbuntuPath(paths.getUbuntuPathForTermuxFile(file));
            }
        }
        return builder.build();
    }

    private void loadDocument(OpenHouseEditorDocument nextDocument) {
        document = nextDocument == null ? newDraftDocument() : nextDocument;
        dirty = false;
        previewMode = false;
        setTitle(document.getDisplayName());
        titleView.setText(document.getDisplayName());
        metaView.setText(buildMetaText(document));
        setLoadingText(getString(R.string.openhouse_editor_loading));
        updatePreviewVisibility();

        if (!document.hasSource()) {
            loadResult = OpenHouseEditorLoadResult.loaded("", 0L, 0L, false, false, "");
            setEditorText("");
            setLoadingText(getString(R.string.openhouse_editor_draft_ready));
            updateButtonsAndStatus();
            return;
        }

        if (!OpenHouseEditorTextTypes.isLikelyText(document.getDisplayName(), document.getMimeType())) {
            String note = getString(R.string.openhouse_editor_non_text);
            loadResult = OpenHouseEditorLoadResult.unsupported(note, document.getSizeBytes());
            setEditorText(note);
            updateButtonsAndStatus();
            return;
        }

        ioExecutor.execute(() -> {
            try (InputStream input = openInputStream(document)) {
                OpenHouseEditorLoadResult result = OpenHouseEditorTextReader.readUtf8(
                    input,
                    document.getSizeBytes(),
                    MAX_EDITABLE_BYTES);
                String note = result.isContentTruncated()
                    ? getString(R.string.openhouse_editor_large_file_read_only)
                    : "";
                OpenHouseEditorLoadResult finalResult = OpenHouseEditorLoadResult.loaded(
                    result.getText(),
                    result.getLoadedBytes(),
                    result.getSourceSizeBytes(),
                    result.isContentTruncated(),
                    result.isReadOnly() || document.isReadOnlyRequested(),
                    note);
                mainHandler.post(() -> {
                    loadResult = finalResult;
                    setEditorText(finalResult.getText());
                    updateButtonsAndStatus();
                });
            } catch (Exception e) {
                mainHandler.post(() -> showLoadError(e));
            }
        });
    }

    private void showLoadError(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        loadResult = OpenHouseEditorLoadResult.unsupported(message, document == null ? -1L : document.getSizeBytes());
        setEditorText(message);
        Toast.makeText(this, getString(R.string.openhouse_editor_open_failed, message), Toast.LENGTH_LONG).show();
        updateButtonsAndStatus();
    }

    private void setEditorText(String text) {
        loadingText = true;
        editorView.setText(text == null ? "" : text);
        editorView.setSelection(0);
        loadingText = false;
        dirty = false;
        updatePreviewVisibility();
    }

    private void updateButtonsAndStatus() {
        boolean hasText = loadResult != null;
        boolean readOnly = isReadOnly();
        boolean canSave = hasText && document != null && document.hasSource() && !readOnly
            && !loadResult.isContentTruncated();
        saveButton.setEnabled(canSave);
        exportButton.setEnabled(hasText);
        previewButton.setEnabled(hasText);
        outlineButton.setEnabled(hasText);
        previewButton.setText(previewMode
            ? getString(R.string.openhouse_editor_edit)
            : getString(R.string.openhouse_editor_preview));
        setEditorReadOnly(readOnly);
        statusView.setText(buildStatusText(readOnly));
    }

    private boolean isReadOnly() {
        return document == null
            || document.isReadOnlyRequested()
            || loadResult == null
            || loadResult.isReadOnly()
            || loadResult.isContentTruncated()
            || !OpenHouseEditorTextTypes.isLikelyText(document.getDisplayName(), document.getMimeType());
    }

    private void setEditorReadOnly(boolean readOnly) {
        editorView.setKeyListener(readOnly ? null : editorKeyListener);
        editorView.setCursorVisible(!readOnly);
        editorView.setFocusableInTouchMode(!readOnly);
    }

    private String buildMetaText(OpenHouseEditorDocument document) {
        StringBuilder builder = new StringBuilder();
        builder.append(valueOrUnknown(document.getMimeType()));
        builder.append(" · ").append(formatSize(document.getSizeBytes()));
        String source = document.sourceSummary();
        if (!source.trim().isEmpty()) {
            builder.append("\n").append(source);
        }
        return builder.toString();
    }

    private String buildStatusText(boolean readOnly) {
        if (document == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(dirty ? getString(R.string.openhouse_editor_status_dirty) : getString(R.string.openhouse_editor_status_clean));
        if (readOnly) {
            builder.append(" · ").append(getString(R.string.openhouse_editor_status_read_only));
        }
        if (document.isRepositoryExport()) {
            builder.append(" · ").append(getString(R.string.openhouse_editor_status_repository_export));
        }
        if (loadResult != null && loadResult.isContentTruncated()) {
            builder.append(" · ").append(getString(R.string.openhouse_editor_status_truncated));
        }
        if (loadResult != null && !loadResult.getNote().trim().isEmpty()) {
            builder.append("\n").append(loadResult.getNote());
        }
        return builder.toString();
    }

    private void setLoadingText(String text) {
        statusView.setText(text);
    }

    private void saveCurrentText() {
        if (document == null || !document.hasSource()) {
            openExportPicker();
            return;
        }
        if (isReadOnly() || loadResult == null || loadResult.isContentTruncated()) {
            Toast.makeText(this, R.string.openhouse_editor_save_disabled, Toast.LENGTH_SHORT).show();
            return;
        }

        final String text = editorView.getText().toString();
        setLoadingText(getString(R.string.openhouse_editor_saving));
        ioExecutor.execute(() -> {
            try {
                File backupFile = createBackup(document);
                long savedAt = System.currentTimeMillis();
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                try (OutputStream output = openOutputStream(document)) {
                    output.write(bytes);
                }
                OpenHouseEditorDocument savedDocument = updateSavedDocumentSize(document, bytes.length);
                OpenHouseEditorLoadResult savedResult = OpenHouseEditorLoadResult.loaded(
                    text,
                    bytes.length,
                    bytes.length,
                    false,
                    savedDocument.isReadOnlyRequested(),
                    "");
                mainHandler.post(() -> {
                    document = savedDocument;
                    loadResult = savedResult;
                    dirty = false;
                    metaView.setText(buildMetaText(document));
                    updateButtonsAndStatus();
                    setResult(Activity.RESULT_OK, buildSaveResultIntent(backupFile, savedAt));
                    Toast.makeText(this, R.string.openhouse_editor_saved, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> showSaveError(e));
            }
        });
    }

    private OpenHouseEditorDocument updateSavedDocumentSize(OpenHouseEditorDocument source, long writtenBytes) {
        long size = writtenBytes;
        if (source.getLocalFile() != null && source.getLocalFile().isFile()) {
            size = source.getLocalFile().length();
        }
        return source.buildUpon().setSizeBytes(size).build();
    }

    private Intent buildSaveResultIntent(File backupFile, long savedAt) {
        Intent result = new Intent();
        if (document != null) {
            if (document.getLocalFile() != null) {
                result.putExtra(OpenHouseEditorContract.EXTRA_SAVED_FILE_PATH, document.getLocalFile().getAbsolutePath());
            }
            if (document.getUri() != null) {
                result.putExtra(OpenHouseEditorContract.EXTRA_SAVED_FILE_URI, document.getUri().toString());
            }
            OpenHouseEditorContract.putIfPresent(result, OpenHouseEditorContract.EXTRA_SOURCE_SPACE_ID, document.getSourceSpaceId());
            OpenHouseEditorContract.putIfPresent(result, OpenHouseEditorContract.EXTRA_SOURCE_FILE_ID, document.getSourceFileId());
        }
        if (backupFile != null) {
            result.putExtra(OpenHouseEditorContract.EXTRA_BACKUP_FILE_PATH, backupFile.getAbsolutePath());
        }
        result.putExtra(OpenHouseEditorContract.EXTRA_SAVED_AT_MILLIS, savedAt);
        return result;
    }

    private void showSaveError(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        updateButtonsAndStatus();
        Toast.makeText(this, getString(R.string.openhouse_editor_save_failed, message), Toast.LENGTH_LONG).show();
    }

    private File createBackup(OpenHouseEditorDocument document) throws IOException {
        File backupRoot = new File(
            OpenHouseWorkspacePaths.forTermuxHome(TermuxConstants.TERMUX_HOME_DIR_PATH).getExportDir(),
            OpenHouseEditorBackupNamer.BACKUP_DIR_NAME);
        if (!backupRoot.isDirectory() && !backupRoot.mkdirs() && !backupRoot.isDirectory()) {
            throw new IOException("Cannot create backup directory: " + backupRoot.getAbsolutePath());
        }
        File backupFile = OpenHouseEditorBackupNamer.buildBackupFile(backupRoot, document.getDisplayName(), System.currentTimeMillis());
        try (InputStream input = openInputStream(document);
             OutputStream output = new FileOutputStream(backupFile)) {
            copy(input, output);
        }
        return backupFile;
    }

    private void openSystemFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_OPEN_DOCUMENT);
    }

    private void openExportPicker() {
        String displayName = document == null ? "untitled.md" : document.getDisplayName();
        String mimeType = document == null ? "text/plain" : document.getMimeType();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(OpenHouseEditorDocument.isBlank(mimeType) ? "text/plain" : mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, displayName);
        startActivityForResult(intent, REQUEST_EXPORT_DOCUMENT);
    }

    private void exportCurrentText(Uri uri) {
        final String text = editorView.getText().toString();
        setLoadingText(getString(R.string.openhouse_editor_exporting));
        ioExecutor.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) {
                    throw new FileNotFoundException(uri.toString());
                }
                output.write(text.getBytes(StandardCharsets.UTF_8));
                mainHandler.post(() -> {
                    updateButtonsAndStatus();
                    Toast.makeText(this, R.string.openhouse_editor_exported, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> showSaveError(e));
            }
        });
    }

    private void togglePreview() {
        previewMode = !previewMode;
        updatePreviewVisibility();
        updateButtonsAndStatus();
    }

    private void updatePreviewVisibility() {
        if (editorView == null || previewScrollView == null) return;
        if (previewMode) {
            String text = editorView.getText().toString();
            if (document != null && OpenHouseEditorTextTypes.isMarkdown(document.getDisplayName(), document.getMimeType())) {
                markwon.setMarkdown(previewView, text);
            } else {
                previewView.setText(text);
            }
            editorView.setVisibility(View.GONE);
            previewScrollView.setVisibility(View.VISIBLE);
        } else {
            editorView.setVisibility(View.VISIBLE);
            previewScrollView.setVisibility(View.GONE);
        }
    }

    private void showOutlineDialog() {
        String text = editorView.getText().toString();
        List<OpenHouseMarkdownHeading> headings = OpenHouseMarkdownOutlineParser.parse(text);
        if (headings.isEmpty()) {
            Toast.makeText(this, R.string.openhouse_editor_no_outline, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[headings.size()];
        for (int i = 0; i < headings.size(); i++) {
            labels[i] = headings.get(i).toDisplayString();
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.openhouse_editor_outline)
            .setItems(labels, (dialog, which) -> jumpToHeading(text, headings.get(which)))
            .show();
    }

    private void jumpToHeading(String text, OpenHouseMarkdownHeading heading) {
        previewMode = false;
        updatePreviewVisibility();
        int offset = offsetForLine(text, heading.getLineNumber());
        editorView.requestFocus();
        editorView.setSelection(Math.min(offset, editorView.length()));
    }

    private void copyAiDescription() {
        String description = buildCurrentAiDescription();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.openhouse_editor_ai_description), description));
        }
        Toast.makeText(this, R.string.openhouse_editor_copied, Toast.LENGTH_SHORT).show();
    }

    private void shareAiDescription() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.openhouse_editor_ai_description) + ": " + (document == null ? "" : document.getDisplayName()));
        intent.putExtra(Intent.EXTRA_TEXT, buildCurrentAiDescription());
        startActivity(Intent.createChooser(intent, getString(R.string.openhouse_editor_share_ai)));
    }

    private String buildCurrentAiDescription() {
        OpenHouseEditorLoadResult current = loadResult;
        if (current != null && editorView != null && !current.isContentTruncated()) {
            long bytes = editorView.getText().toString().getBytes(StandardCharsets.UTF_8).length;
            current = OpenHouseEditorLoadResult.loaded(
                editorView.getText().toString(),
                bytes,
                document == null ? bytes : Math.max(document.getSizeBytes(), bytes),
                false,
                isReadOnly(),
                current.getNote());
        }
        return OpenHouseEditorAiDescription.build(document == null ? newDraftDocument() : document, current);
    }

    private InputStream openInputStream(OpenHouseEditorDocument document) throws IOException {
        if (document.getLocalFile() != null) {
            return new FileInputStream(document.getLocalFile());
        }
        if (document.getUri() != null) {
            InputStream input = getContentResolver().openInputStream(document.getUri());
            if (input != null) return input;
            throw new FileNotFoundException(document.getUri().toString());
        }
        throw new FileNotFoundException("No source");
    }

    private OutputStream openOutputStream(OpenHouseEditorDocument document) throws IOException {
        if (document.getLocalFile() != null) {
            File parent = document.getLocalFile().getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("Cannot create parent directory: " + parent.getAbsolutePath());
            }
            return new FileOutputStream(document.getLocalFile(), false);
        }
        if (document.getUri() != null) {
            OutputStream output = getContentResolver().openOutputStream(document.getUri(), "wt");
            if (output != null) return output;
            throw new FileNotFoundException(document.getUri().toString());
        }
        throw new FileNotFoundException("No target");
    }

    private ContentMetadata queryContentMetadata(Uri uri) {
        if (uri == null) return ContentMetadata.empty();
        String displayName = null;
        String mimeType = null;
        long sizeBytes = -1L;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) sizeBytes = cursor.getLong(sizeIndex);
            }
        } catch (Exception ignored) {
        }
        try {
            mimeType = getContentResolver().getType(uri);
        } catch (Exception ignored) {
        }
        return new ContentMetadata(displayName, mimeType, sizeBytes);
    }

    private void takePersistablePermission(Intent data, Uri uri) {
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (flags == 0 || uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException ignored) {
        }
    }

    private static Uri parseUri(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return Uri.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static long getLongExtra(Intent intent, String key, long fallback) {
        return intent != null && intent.hasExtra(key) ? intent.getLongExtra(key, fallback) : fallback;
    }

    private static String buildAndroidDisplayLocation(Uri uri, String displayName) {
        if (uri == null) return null;
        StringBuilder builder = new StringBuilder();
        if (!trim(uri.getAuthority()).isEmpty()) {
            builder.append(uri.getAuthority());
        } else {
            builder.append("Android content provider");
        }
        if (!trim(displayName).isEmpty()) {
            builder.append(" / ").append(displayName);
        }
        return builder.toString();
    }

    private static int offsetForLine(String text, int lineNumber) {
        if (lineNumber <= 1 || text == null || text.isEmpty()) return 0;
        int currentLine = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                currentLine++;
                if (currentLine == lineNumber) {
                    return i + 1;
                }
            }
        }
        return text.length();
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String trimmed = trim(value);
            if (!trimmed.isEmpty()) return trimmed;
        }
        return "";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String valueOrUnknown(String value) {
        return trim(value).isEmpty() ? "unknown" : value;
    }

    private static String formatSize(long sizeBytes) {
        if (sizeBytes < 0) return "unknown size";
        if (sizeBytes < 1024) return sizeBytes + " B";
        double kib = sizeBytes / 1024d;
        if (kib < 1024d) return String.format(Locale.US, "%.1f KiB", kib);
        double mib = kib / 1024d;
        if (mib < 1024d) return String.format(Locale.US, "%.1f MiB", mib);
        return String.format(Locale.US, "%.1f GiB", mib / 1024d);
    }

    private int color(int resId) {
        return ContextCompat.getColor(this, resId);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout actionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private HorizontalScrollView scrollableRow(LinearLayout row) {
        HorizontalScrollView scrollView = new HorizontalScrollView(this);
        scrollView.setHorizontalScrollBarEnabled(false);
        scrollView.addView(row, new HorizontalScrollView.LayoutParams(
            HorizontalScrollView.LayoutParams.WRAP_CONTENT,
            HorizontalScrollView.LayoutParams.WRAP_CONTENT));
        return scrollView;
    }

    private Button actionButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(13);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(dp(8));
        button.setLayoutParams(params);
        return button;
    }

    private LinearLayout.LayoutParams fullWidthParams() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams topMarginParams(int topDp) {
        LinearLayout.LayoutParams params = fullWidthParams();
        params.topMargin = dp(topDp);
        return params;
    }

    private static final class ContentMetadata {
        private final String displayName;
        private final String mimeType;
        private final long sizeBytes;

        private ContentMetadata(String displayName, String mimeType, long sizeBytes) {
            this.displayName = displayName;
            this.mimeType = mimeType;
            this.sizeBytes = sizeBytes;
        }

        private static ContentMetadata empty() {
            return new ContentMetadata(null, null, -1L);
        }
    }
}
