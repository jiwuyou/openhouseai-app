package com.termux.app.openhouse.files.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.termux.R;
import com.termux.app.openhouse.files.core.OpenHouseAiFileDescriptionBuilder;
import com.termux.app.openhouse.files.core.OpenHouseFileNameSanitizer;
import com.termux.app.openhouse.files.core.OpenHouseFileReference;
import com.termux.app.openhouse.files.core.OpenHouseWorkspacePaths;
import com.termux.app.openhouse.files.importing.OpenHouseInboxGrouping;
import com.termux.app.openhouse.files.model.FileItem;
import com.termux.app.openhouse.files.model.FileOperation;
import com.termux.app.openhouse.files.model.FileOperationException;
import com.termux.app.openhouse.files.model.FileRepository;
import com.termux.app.openhouse.files.storage.FileRepositoryUtils;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OpenHouseFilesActivity extends AppCompatActivity {

    private static final int REQUEST_ADD_SAF_TREE = 4101;
    private static final int REQUEST_UPLOAD_FILE = 4102;
    private static final String FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider";
    private static final int MENU_ACTION_OPEN = 1;
    private static final int MENU_ACTION_DESCRIBE = 2;
    private static final int MENU_ACTION_RENAME = 3;
    private static final int MENU_ACTION_DELETE = 4;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<DirectoryCrumb> directoryStack = new ArrayList<>();
    private final FileItemAdapter fileItemAdapter = new FileItemAdapter();

    private OpenHouseFilesConfigStore configStore;
    private OpenHouseFileSpaceCatalog catalog;
    private SpaceEntryAdapter spaceAdapter;
    private List<OpenHouseFileSpaceEntry> spaces = new ArrayList<>();
    private OpenHouseFileSpaceEntry currentEntry;
    private FileItem selectedItem;
    private String currentDirectoryId = FileItem.ROOT_ID;

    private Spinner spaceSpinner;
    private TextView statusView;
    private TextView pathView;
    private TextView emptyView;
    private ListView listView;
    private Button upButton;
    private Button uploadButton;
    private Button mkdirButton;
    private Button renameButton;
    private Button deleteButton;
    private Button openButton;
    private Button describeButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.openhouse_files_title);
        configStore = new OpenHouseFilesConfigStore(this);
        catalog = new OpenHouseFileSpaceCatalog(this, configStore);
        buildUi();
        reloadSpaces(null);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQUEST_ADD_SAF_TREE) {
            handleSafTreeResult(data);
        } else if (requestCode == REQUEST_UPLOAD_FILE) {
            Uri uri = data.getData();
            if (uri != null) {
                uploadUri(uri);
            }
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));
        setContentView(root);

        LinearLayout spaceRow = new LinearLayout(this);
        spaceRow.setOrientation(LinearLayout.HORIZONTAL);
        spaceRow.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(spaceRow, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        spaceSpinner = new Spinner(this);
        spaceAdapter = new SpaceEntryAdapter(this, spaces);
        spaceSpinner.setAdapter(spaceAdapter);
        spaceRow.addView(spaceSpinner, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        spaceSpinner.post(() -> spaceSpinner.setDropDownWidth(
            Math.max(spaceSpinner.getWidth(), getResources().getDisplayMetrics().widthPixels - dp(24))));

        LinearLayout topControlsRow = actionRow();
        root.addView(topControlsRow);

        Button addSafButton = smallButton("容器");
        addSafButton.setOnClickListener(v -> launchAddSafContainer());
        addActionButton(topControlsRow, addSafButton);

        Button addNetworkButton = smallButton("网络");
        addNetworkButton.setOnClickListener(v -> showAddNetworkDialog());
        addActionButton(topControlsRow, addNetworkButton);

        Button settingsButton = smallButton("设置");
        settingsButton.setOnClickListener(v -> showFilesSettingsDialog());
        addActionButton(topControlsRow, settingsButton);

        pathView = new TextView(this);
        pathView.setTextSize(13);
        pathView.setSingleLine(false);
        pathView.setPadding(0, dp(8), 0, dp(4));
        root.addView(pathView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actionPanel = new LinearLayout(this);
        actionPanel.setOrientation(LinearLayout.VERTICAL);
        root.addView(actionPanel, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout navigationRow = actionRow();
        actionPanel.addView(navigationRow);
        upButton = actionButton("上级", v -> navigateUp());
        addActionButton(navigationRow, upButton);
        addActionButton(navigationRow, actionButton("刷新", v -> loadDirectory(currentDirectoryId, false, currentLabel())));
        uploadButton = actionButton("上传", v -> launchUploadFile());
        addActionButton(navigationRow, uploadButton);

        LinearLayout fileRow = actionRow();
        actionPanel.addView(fileRow);
        mkdirButton = actionButton("新文件夹", v -> showCreateDirectoryDialog());
        addActionButton(fileRow, mkdirButton);
        renameButton = actionButton("重命名", v -> showRenameDialog());
        addActionButton(fileRow, renameButton);
        deleteButton = actionButton("删除", v -> showDeleteDialog());
        addActionButton(fileRow, deleteButton);

        LinearLayout selectedRow = actionRow();
        actionPanel.addView(selectedRow);
        openButton = actionButton("打开", v -> openSelectedItem());
        addActionButton(selectedRow, openButton);
        describeButton = actionButton("AI说明", v -> describeSelectedItem());
        addActionButton(selectedRow, describeButton);

        statusView = new TextView(this);
        statusView.setTextSize(12);
        statusView.setPadding(0, dp(6), 0, dp(6));
        root.addView(statusView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        emptyView = new TextView(this);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setText(R.string.openhouse_files_empty);

        listView = new ListView(this);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        listView.setAdapter(fileItemAdapter);
        listView.setEmptyView(emptyView);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            FileItem item = fileItemAdapter.getItem(position);
            selectItem(item);
            if (item != null) openItem(item);
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            selectItem(fileItemAdapter.getItem(position));
            return true;
        });

        FrameLayout listFrame = new FrameLayout(this);
        listFrame.addView(listView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listFrame.addView(emptyView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(listFrame, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        spaceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < spaces.size()) {
                    setCurrentSpace(spaces.get(position));
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void reloadSpaces(String preferredId) {
        spaces = catalog.loadEntries();
        spaceAdapter.clear();
        spaceAdapter.addAll(spaces);
        spaceAdapter.notifyDataSetChanged();
        if (spaces.isEmpty()) {
            setStatus("没有可用文件空间");
            return;
        }
        int selected = 0;
        if (preferredId != null) {
            for (int i = 0; i < spaces.size(); i++) {
                if (preferredId.equals(spaces.get(i).getId())) {
                    selected = i;
                    break;
                }
            }
        } else if (currentEntry != null) {
            for (int i = 0; i < spaces.size(); i++) {
                if (currentEntry.getId().equals(spaces.get(i).getId())) {
                    selected = i;
                    break;
                }
            }
        }
        spaceSpinner.setSelection(selected);
        setCurrentSpace(spaces.get(selected));
    }

    private void setCurrentSpace(OpenHouseFileSpaceEntry entry) {
        if (entry == null) return;
        if (currentEntry != null && currentEntry.getId().equals(entry.getId())) {
            return;
        }
        currentEntry = entry;
        selectedItem = null;
        currentDirectoryId = FileItem.ROOT_ID;
        directoryStack.clear();
        directoryStack.add(new DirectoryCrumb(FileItem.ROOT_ID, entry.getDisplayName()));
        fileItemAdapter.setItems(new ArrayList<>());
        updatePath();
        updateActionState();
        loadDirectory(FileItem.ROOT_ID, false, entry.getDisplayName());
    }

    private void loadDirectory(final String directoryId, final boolean push, final String label) {
        final FileRepository repository = currentRepository();
        if (repository == null) return;
        setStatus("正在读取...");
        runIo(new IoTask<List<FileItem>>() {
            @Override
            public List<FileItem> run() throws Exception {
                repository.getRoot();
                return repository.list(directoryId);
            }
        }, new UiTask<List<FileItem>>() {
            @Override
            public void run(List<FileItem> items) {
                List<FileItem> visibleItems = filterHiddenItems(items);
                currentDirectoryId = directoryId == null ? FileItem.ROOT_ID : directoryId;
                if (push) {
                    directoryStack.add(new DirectoryCrumb(currentDirectoryId, label));
                } else if (directoryStack.isEmpty()) {
                    directoryStack.add(new DirectoryCrumb(currentDirectoryId, label));
                } else {
                    directoryStack.set(directoryStack.size() - 1, new DirectoryCrumb(currentDirectoryId, label));
                }
                selectedItem = null;
                listView.clearChoices();
                fileItemAdapter.setItems(visibleItems);
                setStatus(buildDirectoryStatus(items, visibleItems));
                updatePath();
                updateActionState();
            }
        });
    }

    private void openItem(FileItem item) {
        if (item == null) return;
        if (item.isDirectory()) {
            loadDirectory(item.getId(), true, item.getDisplayName());
            return;
        }
        if (isTextLike(item)) {
            openInEditor(item);
        } else {
            exportAndOpenExternally(item);
        }
    }

    private void openSelectedItem() {
        FileItem item = requireSelectedItem();
        if (item != null) openItem(item);
    }

    private void openInEditor(final FileItem item) {
        String nativeLocation = item.getNativeLocation();
        if (isFileUri(nativeLocation) || isContentUri(nativeLocation)) {
            startEditor(item, null);
            return;
        }
        exportItem(item, "editor", new UiTask<File>() {
            @Override
            public void run(File file) {
                startEditor(item, file);
            }
        });
    }

    private void startEditor(FileItem item, File tempCopy) {
        Intent intent = OpenHouseFilesEditorIntents.createOpenIntent(
            this,
            item,
            tempCopy,
            currentEntry == null ? "" : currentEntry.getId());
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "编辑器入口尚不可用", Toast.LENGTH_SHORT).show();
        }
    }

    private void exportAndOpenExternally(final FileItem item) {
        exportItem(item, "open", new UiTask<File>() {
            @Override
            public void run(File file) {
                openExternalFile(file, item.getMimeType());
            }
        });
    }

    private void exportItem(final FileItem item, final String purpose, final UiTask<File> onExported) {
        final FileRepository repository = currentRepository();
        if (repository == null) return;
        setStatus("正在导出...");
        runIo(new IoTask<File>() {
            @Override
            public File run() throws Exception {
                OpenHouseWorkspacePaths paths = OpenHouseWorkspacePaths.forTermuxHome(TermuxConstants.TERMUX_HOME_DIR_PATH);
                File exportDir = new File(paths.getExportDir(), purpose);
                if (!exportDir.isDirectory() && !exportDir.mkdirs()) {
                    throw new FileOperationException(FileOperationException.Code.PERMISSION_DENIED,
                        "Cannot create export directory: " + exportDir.getAbsolutePath());
                }
                File target = uniqueFile(exportDir, OpenHouseFileNameSanitizer.sanitize(item.getDisplayName(), "file"));
                try (OutputStream output = new FileOutputStream(target)) {
                    repository.download(item.getId(), output);
                }
                return target;
            }
        }, new UiTask<File>() {
            @Override
            public void run(File file) {
                setStatus("已导出: " + file.getAbsolutePath());
                onExported.run(file);
            }
        });
    }

    private void openExternalFile(File file, String mimeType) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + FILE_PROVIDER_AUTHORITY_SUFFIX, file);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, firstNonBlank(mimeType, "application/octet-stream"));
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, "用其他应用打开"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void launchUploadFile() {
        if (!supportsCurrent(FileOperation.UPLOAD)) {
            Toast.makeText(this, "当前空间不支持上传", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_UPLOAD_FILE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "没有可用文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void uploadUri(final Uri uri) {
        final FileRepository repository = currentRepository();
        if (repository == null) return;
        setStatus("正在上传...");
        runIo(new IoTask<FileItem>() {
            @Override
            public FileItem run() throws Exception {
                ContentMetadata metadata = readContentMetadata(uri);
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) {
                        throw new FileOperationException(FileOperationException.Code.NOT_FOUND, "Cannot open selected file");
                    }
                    return repository.upload(currentDirectoryId, metadata.displayName, input, metadata.size, metadata.mimeType);
                }
            }
        }, new UiTask<FileItem>() {
            @Override
            public void run(FileItem result) {
                setStatus("已上传: " + result.getDisplayName());
                loadDirectory(currentDirectoryId, false, currentLabel());
            }
        });
    }

    private void showCreateDirectoryDialog() {
        if (!supportsCurrent(FileOperation.CREATE_DIRECTORY)) {
            Toast.makeText(this, "当前空间不支持新建文件夹", Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        showKeyboard(input);
        new AlertDialog.Builder(this)
            .setTitle("新文件夹")
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> createDirectory(input.getText().toString()))
            .show();
    }

    private void createDirectory(final String displayName) {
        final FileRepository repository = currentRepository();
        if (repository == null) return;
        runIo(new IoTask<FileItem>() {
            @Override
            public FileItem run() throws Exception {
                return repository.createDirectory(currentDirectoryId, displayName);
            }
        }, new UiTask<FileItem>() {
            @Override
            public void run(FileItem item) {
                setStatus("已创建: " + item.getDisplayName());
                loadDirectory(currentDirectoryId, false, currentLabel());
            }
        });
    }

    private void showRenameDialog() {
        final FileItem item = requireSelectedItem();
        if (item == null) return;
        if (!supportsCurrent(FileOperation.RENAME)) {
            Toast.makeText(this, "当前空间不支持重命名", Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(item.getDisplayName());
        input.setSelectAllOnFocus(true);
        showKeyboard(input);
        new AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> renameItem(item, input.getText().toString()))
            .show();
    }

    private void renameItem(final FileItem item, final String newName) {
        final FileRepository repository = currentRepository();
        if (repository == null) return;
        runIo(new IoTask<FileItem>() {
            @Override
            public FileItem run() throws Exception {
                return repository.rename(item.getId(), newName);
            }
        }, new UiTask<FileItem>() {
            @Override
            public void run(FileItem renamed) {
                setStatus("已重命名: " + renamed.getDisplayName());
                loadDirectory(currentDirectoryId, false, currentLabel());
            }
        });
    }

    private void showDeleteDialog() {
        final FileItem item = requireSelectedItem();
        if (item == null) return;
        if (!supportsCurrent(FileOperation.DELETE) || !item.isDeletable()) {
            Toast.makeText(this, "当前项目不可删除", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("删除")
            .setMessage(item.getDisplayName())
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> deleteItem(item))
            .show();
    }

    private void deleteItem(final FileItem item) {
        final FileRepository repository = currentRepository();
        if (repository == null) return;
        runIo(new IoTask<Void>() {
            @Override
            public Void run() throws Exception {
                repository.delete(item.getId());
                return null;
            }
        }, new UiTask<Void>() {
            @Override
            public void run(Void ignored) {
                setStatus("已删除: " + item.getDisplayName());
                loadDirectory(currentDirectoryId, false, currentLabel());
            }
        });
    }

    private void describeSelectedItem() {
        FileItem item = requireSelectedItem();
        if (item == null) return;
        final String description = OpenHouseAiFileDescriptionBuilder.build(buildReference(item));
        new AlertDialog.Builder(this)
            .setTitle("AI 文件说明")
            .setItems(new CharSequence[]{"复制说明", "给 AI 描述"}, (dialog, which) -> {
                if (which == 0) {
                    copyText(description);
                } else {
                    shareText(description, item.getDisplayName());
                }
            })
            .show();
    }

    private OpenHouseFileReference buildReference(FileItem item) {
        OpenHouseWorkspacePaths paths = OpenHouseWorkspacePaths.forTermuxHome(TermuxConstants.TERMUX_HOME_DIR_PATH);
        String nativeLocation = firstNonBlank(item.getNativeLocation(), "");
        String androidUri = isContentUri(nativeLocation) ? nativeLocation : "";
        String androidDisplayLocation = nativeLocation.isEmpty()
            ? (currentEntry == null ? "" : currentEntry.getSummary())
            : nativeLocation;
        String termuxPath = "";
        String ubuntuPath = "";
        String workspacePath = "";
        File localFile = fileFromNativeLocation(nativeLocation);
        if (localFile != null) {
            termuxPath = OpenHouseWorkspacePaths.normalize(localFile.getAbsolutePath());
            String mappedUbuntu = paths.getUbuntuPathForTermuxFile(localFile);
            if (mappedUbuntu != null) ubuntuPath = mappedUbuntu;
            String relative = paths.getWorkspaceRelativePath(localFile);
            if (relative != null) workspacePath = paths.getOpenHouseWorkspacePath(localFile);
        }
        return OpenHouseFileReference.builder()
            .setDisplayName(item.getDisplayName())
            .setMimeType(item.getMimeType())
            .setSizeBytes(item.getSize())
            .setAndroidUri(androidUri)
            .setAndroidDisplayLocation(androidDisplayLocation)
            .setTermuxPath(termuxPath)
            .setUbuntuPath(ubuntuPath)
            .setWorkspacePath(workspacePath)
            .build();
    }

    private void copyText(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("OpenHouse 文件说明", text));
        }
        Toast.makeText(this, "已复制说明", Toast.LENGTH_SHORT).show();
    }

    private void shareText(String text, String displayName) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "OpenHouse 文件说明: " + displayName);
        intent.putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(intent, "给 AI 描述"));
        } catch (ActivityNotFoundException e) {
            copyText(text);
        }
    }

    private void launchAddSafContainer() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_ADD_SAF_TREE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "没有可用文件容器选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleSafTreeResult(Intent data) {
        Uri treeUri = data.getData();
        if (treeUri == null) return;
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(treeUri, flags);
        } catch (Exception ignored) {
        }
        String displayName = buildSafTreeName(treeUri);
        OpenHouseFilesConfigStore.SafContainerRecord record = configStore.addSafContainer(displayName, treeUri);
        reloadSpaces(record.id);
    }

    private void showAddNetworkDialog() {
        new AlertDialog.Builder(this)
            .setTitle("添加网络存储")
            .setItems(new CharSequence[]{"WebDAV", "S3-compatible"}, (dialog, which) -> {
                if (which == 0) {
                    showWebDavDialog();
                } else {
                    showS3Dialog();
                }
            })
            .show();
    }

    private void showFilesSettingsDialog() {
        final OpenHouseInboxGrouping[] groupings = new OpenHouseInboxGrouping[]{
            OpenHouseInboxGrouping.NONE,
            OpenHouseInboxGrouping.DAY,
            OpenHouseInboxGrouping.MONTH,
            OpenHouseInboxGrouping.YEAR
        };
        OpenHouseInboxGrouping current = configStore.getInboxGrouping();

        LinearLayout form = formLayout();
        TextView groupingTitle = new TextView(this);
        groupingTitle.setText("中转站分组");
        groupingTitle.setTextSize(15);
        groupingTitle.setPadding(0, 0, 0, dp(4));
        form.addView(groupingTitle);

        RadioGroup groupingGroup = new RadioGroup(this);
        groupingGroup.setOrientation(RadioGroup.VERTICAL);
        int[] radioIds = new int[groupings.length];
        for (int i = 0; i < groupings.length; i++) {
            RadioButton button = new RadioButton(this);
            button.setText(inboxGroupingLabel(groupings[i]));
            button.setId(View.generateViewId());
            radioIds[i] = button.getId();
            groupingGroup.addView(button);
            if (groupings[i] == current) groupingGroup.check(button.getId());
        }
        groupingGroup.setOnCheckedChangeListener((group, checkedId) -> {
            for (int i = 0; i < radioIds.length; i++) {
                if (radioIds[i] == checkedId) {
                    configStore.setInboxGrouping(groupings[i]);
                    setStatus("中转站分组: " + inboxGroupingLabel(groupings[i]));
                    return;
                }
            }
        });
        form.addView(groupingGroup);

        CheckBox showHiddenFiles = new CheckBox(this);
        showHiddenFiles.setText("显示隐藏文件");
        showHiddenFiles.setChecked(configStore.shouldShowHiddenFiles());
        showHiddenFiles.setOnCheckedChangeListener((buttonView, isChecked) -> {
            configStore.setShowHiddenFiles(isChecked);
            reloadCurrentDirectory();
        });
        form.addView(showHiddenFiles);

        new AlertDialog.Builder(this)
            .setTitle("文件设置")
            .setView(form)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private String inboxGroupingLabel(OpenHouseInboxGrouping grouping) {
        if (grouping == OpenHouseInboxGrouping.NONE) return "不分组";
        if (grouping == OpenHouseInboxGrouping.DAY) return "按天";
        if (grouping == OpenHouseInboxGrouping.YEAR) return "按年";
        return "按月";
    }

    private void showWebDavDialog() {
        LinearLayout form = formLayout();
        EditText name = formInput(form, "名称", "WebDAV");
        EditText url = formInput(form, "地址", "https://example.com/dav/");
        EditText username = formInput(form, "用户名", "");
        EditText password = formInput(form, "密码", "");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
            .setTitle("WebDAV")
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                try {
                    OpenHouseFilesConfigStore.WebDavRecord record = configStore.addWebDav(
                        name.getText().toString(),
                        url.getText().toString(),
                        username.getText().toString(),
                        password.getText().toString());
                    reloadSpaces(record.id);
                } catch (Exception e) {
                    showError("WebDAV 配置无效", e);
                }
            })
            .show();
    }

    private void showS3Dialog() {
        LinearLayout form = formLayout();
        EditText name = formInput(form, "名称", "S3");
        EditText endpoint = formInput(form, "Endpoint", "https://s3.amazonaws.com");
        EditText region = formInput(form, "Region", "us-east-1");
        EditText bucket = formInput(form, "Bucket", "");
        EditText accessKey = formInput(form, "Access key", "");
        EditText secretKey = formInput(form, "Secret key", "");
        secretKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText sessionToken = formInput(form, "Session token", "");
        sessionToken.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CheckBox pathStyle = new CheckBox(this);
        pathStyle.setText("Path-style access");
        pathStyle.setChecked(true);
        form.addView(pathStyle);
        new AlertDialog.Builder(this)
            .setTitle("S3-compatible")
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                try {
                    OpenHouseFilesConfigStore.S3Record record = configStore.addS3(
                        name.getText().toString(),
                        endpoint.getText().toString(),
                        region.getText().toString(),
                        bucket.getText().toString(),
                        accessKey.getText().toString(),
                        secretKey.getText().toString(),
                        sessionToken.getText().toString(),
                        pathStyle.isChecked());
                    reloadSpaces(record.id);
                } catch (Exception e) {
                    showError("S3 配置无效", e);
                }
            })
            .show();
    }

    private void navigateUp() {
        if (directoryStack.size() <= 1) return;
        directoryStack.remove(directoryStack.size() - 1);
        DirectoryCrumb parent = directoryStack.get(directoryStack.size() - 1);
        loadDirectory(parent.id, false, parent.label);
    }

    private FileRepository currentRepository() {
        return currentEntry == null ? null : currentEntry.getRepository();
    }

    private boolean supportsCurrent(FileOperation operation) {
        FileRepository repository = currentRepository();
        return repository != null && repository.supports(operation);
    }

    private FileItem requireSelectedItem() {
        if (selectedItem == null) {
            Toast.makeText(this, "先选择一个文件或文件夹", Toast.LENGTH_SHORT).show();
        }
        return selectedItem;
    }

    private void selectItem(FileItem item) {
        selectedItem = item;
        if (listView != null) {
            int position = fileItemAdapter.indexOf(item);
            if (position >= 0) {
                listView.setItemChecked(position, true);
            } else {
                listView.clearChoices();
            }
        }
        updateActionState();
    }

    private void showItemContextMenu(final FileItem item, View anchor) {
        if (item == null) return;
        selectItem(item);
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, MENU_ACTION_OPEN, 0, item.isDirectory() ? "进入" : "打开");
        popup.getMenu().add(0, MENU_ACTION_DESCRIBE, 1, "AI说明");
        if (supportsCurrent(FileOperation.RENAME)) {
            popup.getMenu().add(0, MENU_ACTION_RENAME, 2, "重命名");
        }
        if (supportsCurrent(FileOperation.DELETE) && item.isDeletable()) {
            popup.getMenu().add(0, MENU_ACTION_DELETE, 3, "删除");
        }
        popup.setOnMenuItemClickListener(menuItem -> {
            selectItem(item);
            int action = menuItem.getItemId();
            if (action == MENU_ACTION_OPEN) {
                openItem(item);
            } else if (action == MENU_ACTION_DESCRIBE) {
                describeSelectedItem();
            } else if (action == MENU_ACTION_RENAME) {
                showRenameDialog();
            } else if (action == MENU_ACTION_DELETE) {
                showDeleteDialog();
            }
            return true;
        });
        popup.show();
    }

    private void updateActionState() {
        boolean hasSpace = currentEntry != null;
        boolean hasSelection = selectedItem != null;
        upButton.setEnabled(directoryStack.size() > 1);
        uploadButton.setEnabled(hasSpace && supportsCurrent(FileOperation.UPLOAD));
        mkdirButton.setEnabled(hasSpace && supportsCurrent(FileOperation.CREATE_DIRECTORY));
        renameButton.setEnabled(hasSelection && supportsCurrent(FileOperation.RENAME));
        deleteButton.setEnabled(hasSelection && selectedItem.isDeletable() && supportsCurrent(FileOperation.DELETE));
        openButton.setEnabled(hasSelection);
        describeButton.setEnabled(hasSelection);
    }

    private void updatePath() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < directoryStack.size(); i++) {
            if (i > 0) builder.append(" / ");
            builder.append(directoryStack.get(i).label);
        }
        pathView.setText(builder.toString());
    }

    private String currentLabel() {
        if (directoryStack.isEmpty()) return currentEntry == null ? "" : currentEntry.getDisplayName();
        return directoryStack.get(directoryStack.size() - 1).label;
    }

    private void reloadCurrentDirectory() {
        if (currentEntry == null) return;
        loadDirectory(currentDirectoryId, false, currentLabel());
    }

    private List<FileItem> filterHiddenItems(List<FileItem> items) {
        if (items == null) return new ArrayList<>();
        if (configStore == null || configStore.shouldShowHiddenFiles()) return items;
        List<FileItem> visibleItems = new ArrayList<>();
        for (FileItem item : items) {
            if (!isHiddenItem(item)) visibleItems.add(item);
        }
        return visibleItems;
    }

    private boolean isHiddenItem(FileItem item) {
        String displayName = item == null ? "" : item.getDisplayName();
        return displayName.startsWith(".");
    }

    private String buildDirectoryStatus(List<FileItem> allItems, List<FileItem> visibleItems) {
        int total = allItems == null ? 0 : allItems.size();
        int visible = visibleItems == null ? 0 : visibleItems.size();
        int hidden = Math.max(0, total - visible);
        if (hidden > 0) return visible + " 项 · 已隐藏 " + hidden + " 项";
        return visible + " 项";
    }

    private void setStatus(String status) {
        statusView.setText(status == null ? "" : status);
    }

    private <T> void runIo(final IoTask<T> task, final UiTask<T> onSuccess) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final T result = task.run();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onSuccess.run(result);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            showError("文件操作失败", e);
                        }
                    });
                }
            }
        });
    }

    private void showError(String title, Exception e) {
        String message = e == null ? "未知错误" : firstNonBlank(e.getMessage(), e.getClass().getSimpleName());
        setStatus(message);
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private ContentMetadata readContentMetadata(Uri uri) {
        String displayName = null;
        long size = -1;
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex);
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        if (displayName == null || displayName.trim().isEmpty()) {
            String last = uri.getLastPathSegment();
            displayName = last == null ? "upload" : last;
        }
        String mimeType = getContentResolver().getType(uri);
        return new ContentMetadata(OpenHouseFileNameSanitizer.sanitize(displayName, "upload"), size,
            firstNonBlank(mimeType, FileRepositoryUtils.guessMimeType(displayName, false)));
    }

    private boolean isTextLike(FileItem item) {
        String mimeType = firstNonBlank(item.getMimeType(), "").toLowerCase(Locale.US);
        String name = firstNonBlank(item.getDisplayName(), "").toLowerCase(Locale.US);
        if (mimeType.startsWith("text/") || mimeType.contains("json") || mimeType.contains("xml")
            || mimeType.contains("javascript") || mimeType.contains("x-sh")) {
            return true;
        }
        String[] textExtensions = new String[]{
            ".md", ".markdown", ".txt", ".json", ".xml", ".yaml", ".yml", ".toml", ".ini",
            ".conf", ".properties", ".gradle", ".java", ".kt", ".js", ".ts", ".tsx", ".jsx",
            ".py", ".sh", ".css", ".html", ".htm", ".c", ".cc", ".cpp", ".h", ".hpp",
            ".rs", ".go", ".rb", ".php", ".sql", ".log", ".csv"
        };
        for (String extension : textExtensions) {
            if (name.endsWith(extension)) return true;
        }
        return false;
    }

    private File fileFromNativeLocation(String nativeLocation) {
        if (!isFileUri(nativeLocation)) return null;
        try {
            String path = Uri.parse(nativeLocation).getPath();
            return path == null ? null : new File(path);
        } catch (Exception e) {
            return null;
        }
    }

    private static File uniqueFile(File dir, String displayName) {
        File candidate = new File(dir, displayName);
        if (!candidate.exists()) return candidate;
        int dot = displayName.lastIndexOf('.');
        String base = dot > 0 ? displayName.substring(0, dot) : displayName;
        String extension = dot > 0 ? displayName.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            candidate = new File(dir, base + "-" + i + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + extension);
    }

    private String buildSafTreeName(Uri treeUri) {
        try {
            String treeId = DocumentsContract.getTreeDocumentId(treeUri);
            if (treeId != null && !treeId.trim().isEmpty()) return "容器 " + treeId;
        } catch (Exception ignored) {
        }
        return "SAF 容器";
    }

    private LinearLayout formLayout() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(6);
        form.setPadding(padding, padding, padding, padding);
        return form;
    }

    private EditText formInput(LinearLayout form, String hint, String value) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setText(value);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        form.addView(input, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return input;
    }

    private Button smallButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinWidth(dp(64));
        return button;
    }

    private Button actionButton(String label, View.OnClickListener listener) {
        Button button = smallButton(label);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, dp(6), 0);
        button.setLayoutParams(params);
        return button;
    }

    private LinearLayout actionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(4));
        row.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void addActionButton(LinearLayout row, Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(0, 0, dp(6), 0);
        button.setLayoutParams(params);
        row.addView(button);
    }

    private void showKeyboard(final EditText input) {
        input.postDelayed(new Runnable() {
            @Override
            public void run() {
                input.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 200);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private interface IoTask<T> {
        T run() throws Exception;
    }

    private interface UiTask<T> {
        void run(T result);
    }

    private static final class DirectoryCrumb {
        final String id;
        final String label;

        DirectoryCrumb(String id, String label) {
            this.id = id == null ? FileItem.ROOT_ID : id;
            this.label = firstNonBlank(label, "目录");
        }
    }

    private static final class ContentMetadata {
        final String displayName;
        final long size;
        final String mimeType;

        ContentMetadata(String displayName, long size, String mimeType) {
            this.displayName = displayName;
            this.size = size;
            this.mimeType = mimeType;
        }
    }

    private final class SpaceEntryAdapter extends ArrayAdapter<OpenHouseFileSpaceEntry> {

        SpaceEntryAdapter(Context context, List<OpenHouseFileSpaceEntry> entries) {
            super(context, 0, entries);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView view = spaceTextView(convertView);
            OpenHouseFileSpaceEntry entry = getItem(position);
            view.setSingleLine(true);
            view.setMaxLines(1);
            view.setText(entry == null ? "" : entry.getDisplayName());
            return view;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            TextView view = spaceTextView(convertView);
            OpenHouseFileSpaceEntry entry = getItem(position);
            view.setSingleLine(false);
            view.setMaxLines(2);
            view.setText(entry == null ? "" : spaceDropdownText(entry));
            return view;
        }

        private TextView spaceTextView(View convertView) {
            TextView view = convertView instanceof TextView ? (TextView) convertView : new TextView(getContext());
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setTextSize(15);
            view.setEllipsize(TextUtils.TruncateAt.END);
            view.setPadding(dp(8), dp(8), dp(8), dp(8));
            return view;
        }

        private String spaceDropdownText(OpenHouseFileSpaceEntry entry) {
            String summary = entry.getSummary();
            if (summary == null || summary.trim().isEmpty()) {
                return entry.getDisplayName();
            }
            return entry.getDisplayName() + "\n" + summary;
        }
    }

    private final class FileItemAdapter extends BaseAdapter {
        private final List<FileItem> items = new ArrayList<>();
        private final DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);

        void setItems(List<FileItem> newItems) {
            items.clear();
            if (newItems != null) items.addAll(newItems);
            notifyDataSetChanged();
        }

        int indexOf(FileItem item) {
            if (item == null) return -1;
            for (int i = 0; i < items.size(); i++) {
                FileItem candidate = items.get(i);
                if (candidate == item) return i;
                if (candidate != null
                    && firstNonBlank(candidate.getSpaceId(), "").equals(firstNonBlank(item.getSpaceId(), ""))
                    && firstNonBlank(candidate.getId(), "").equals(firstNonBlank(item.getId(), ""))) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public FileItem getItem(int position) {
            return position >= 0 && position < items.size() ? items.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            FileItemRowHolder holder;
            if (convertView instanceof LinearLayout && convertView.getTag() instanceof FileItemRowHolder) {
                row = (LinearLayout) convertView;
                holder = (FileItemRowHolder) row.getTag();
            } else {
                row = new LinearLayout(OpenHouseFilesActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(8), dp(6), dp(4), dp(6));

                TextView icon = new TextView(OpenHouseFilesActivity.this);
                icon.setTextSize(20);
                icon.setGravity(Gravity.CENTER);
                row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

                LinearLayout textColumn = new LinearLayout(OpenHouseFilesActivity.this);
                textColumn.setOrientation(LinearLayout.VERTICAL);
                textColumn.setGravity(Gravity.CENTER_VERTICAL);

                TextView title = new TextView(OpenHouseFilesActivity.this);
                title.setTextSize(16);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);

                TextView summary = new TextView(OpenHouseFilesActivity.this);
                summary.setTextSize(12);
                summary.setSingleLine(true);
                summary.setEllipsize(TextUtils.TruncateAt.END);

                textColumn.addView(title);
                textColumn.addView(summary);
                row.addView(textColumn, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

                Button overflow = smallButton("⋮");
                overflow.setContentDescription("更多操作");
                overflow.setMinWidth(dp(44));
                overflow.setMinimumWidth(dp(44));
                overflow.setPadding(0, 0, 0, 0);
                overflow.setFocusable(false);
                overflow.setFocusableInTouchMode(false);
                row.addView(overflow, new LinearLayout.LayoutParams(
                    dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));

                holder = new FileItemRowHolder(icon, title, summary, overflow);
                row.setTag(holder);
            }
            FileItem item = getItem(position);
            if (item == null) {
                holder.icon.setText("");
                holder.title.setText("");
                holder.summary.setText("");
                holder.overflow.setOnClickListener(null);
                holder.overflow.setEnabled(false);
                return row;
            }
            FileTypeInfo typeInfo = fileTypeInfo(item);
            holder.icon.setText(typeInfo.icon);
            holder.title.setText(item.getDisplayName());
            holder.summary.setText(buildSummary(item, typeInfo.label));
            holder.overflow.setEnabled(true);
            holder.overflow.setOnClickListener(v -> showItemContextMenu(item, v));
            return row;
        }

        private String buildSummary(FileItem item, String typeLabel) {
            StringBuilder builder = new StringBuilder();
            builder.append(typeLabel);
            if (!item.isDirectory()) builder.append(" · ").append(formatSize(item.getSize()));
            if (item.getLastModifiedMillis() > 0) {
                builder.append(" · ").append(dateFormat.format(new Date(item.getLastModifiedMillis())));
            }
            return builder.toString();
        }

        private String formatSize(long size) {
            if (size < 0) return "大小未知";
            if (size < 1024) return size + " B";
            double kib = size / 1024d;
            if (kib < 1024) return String.format(Locale.US, "%.1f KiB", kib);
            double mib = kib / 1024d;
            if (mib < 1024) return String.format(Locale.US, "%.1f MiB", mib);
            return String.format(Locale.US, "%.1f GiB", mib / 1024d);
        }

        private FileTypeInfo fileTypeInfo(FileItem item) {
            if (item.isDirectory()) return new FileTypeInfo("📁", "文件夹");
            String mimeType = firstNonBlank(item.getMimeType(), "").toLowerCase(Locale.US);
            String name = firstNonBlank(item.getDisplayName(), "").toLowerCase(Locale.US);
            if (mimeType.contains("pdf") || name.endsWith(".pdf")) return new FileTypeInfo("PDF", "PDF");
            if (mimeType.startsWith("image/")) return new FileTypeInfo("🖼", "图片");
            if (mimeType.startsWith("audio/")) return new FileTypeInfo("♪", "音频");
            if (mimeType.startsWith("video/")) return new FileTypeInfo("▶", "视频");
            if (isArchive(name, mimeType)) return new FileTypeInfo("ZIP", "压缩包");
            if (isTable(name, mimeType)) return new FileTypeInfo("表", "表格");
            if (isCode(name, mimeType)) return new FileTypeInfo("{}", "代码");
            if (isTextLike(item)) return new FileTypeInfo("TXT", "文本");
            return new FileTypeInfo("📄", "文件");
        }

        private boolean isArchive(String name, String mimeType) {
            return mimeType.contains("zip") || mimeType.contains("tar") || mimeType.contains("gzip")
                || mimeType.contains("compressed") || hasAnyExtension(name, ".zip", ".rar", ".7z", ".tar",
                ".gz", ".tgz", ".bz2", ".xz", ".apk", ".jar");
        }

        private boolean isTable(String name, String mimeType) {
            return mimeType.contains("spreadsheet") || mimeType.contains("excel") || mimeType.contains("csv")
                || hasAnyExtension(name, ".csv", ".tsv", ".xls", ".xlsx", ".ods");
        }

        private boolean isCode(String name, String mimeType) {
            return mimeType.contains("json") || mimeType.contains("xml") || mimeType.contains("javascript")
                || mimeType.contains("x-sh") || hasAnyExtension(name, ".java", ".kt", ".kts", ".js",
                ".ts", ".tsx", ".jsx", ".py", ".sh", ".bash", ".zsh", ".css", ".scss", ".html",
                ".htm", ".c", ".cc", ".cpp", ".h", ".hpp", ".rs", ".go", ".rb", ".php", ".sql",
                ".gradle", ".xml", ".json", ".yaml", ".yml", ".toml");
        }

        private boolean hasAnyExtension(String name, String... extensions) {
            for (String extension : extensions) {
                if (name.endsWith(extension)) return true;
            }
            return false;
        }
    }

    private static final class FileTypeInfo {
        final String icon;
        final String label;

        FileTypeInfo(String icon, String label) {
            this.icon = icon;
            this.label = label;
        }
    }

    private static final class FileItemRowHolder {
        final TextView icon;
        final TextView title;
        final TextView summary;
        final Button overflow;

        FileItemRowHolder(TextView icon, TextView title, TextView summary, Button overflow) {
            this.icon = icon;
            this.title = title;
            this.summary = summary;
            this.overflow = overflow;
        }
    }
}
