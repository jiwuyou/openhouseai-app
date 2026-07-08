package com.termux.app.openhouse.files.ui;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;

import com.termux.app.openhouse.files.core.OpenHouseWorkspacePaths;
import com.termux.app.openhouse.files.model.FileOperation;
import com.termux.app.openhouse.files.model.FileRepository;
import com.termux.app.openhouse.files.model.FileSpace;
import com.termux.app.openhouse.files.model.FileSpaceType;
import com.termux.app.openhouse.files.network.s3.S3FileRepository;
import com.termux.app.openhouse.files.network.s3.S3ObjectStoreConfig;
import com.termux.app.openhouse.files.network.webdav.WebDavConfig;
import com.termux.app.openhouse.files.network.webdav.WebDavFileRepository;
import com.termux.app.openhouse.files.saf.SafDocumentRepository;
import com.termux.app.openhouse.files.saf.SafFileSpaceConfig;
import com.termux.app.openhouse.files.storage.LocalFileRepository;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import okhttp3.OkHttpClient;

public final class OpenHouseFileSpaceCatalog {

    public static final String SPACE_OPENHOUSE_WORKSPACE = "openhouse-workspace";
    public static final String SPACE_TERMUX_HOME = "termux-home";
    public static final String SPACE_UBUNTU_ROOT = "ubuntu-root";
    public static final String SPACE_UBUNTU_WORKSPACE = "ubuntu-workspace";
    public static final String SPACE_ANDROID_SHARED = "android-shared";

    private final Context context;
    private final OpenHouseFilesConfigStore configStore;
    private final OkHttpClient httpClient;

    public OpenHouseFileSpaceCatalog(Context context, OpenHouseFilesConfigStore configStore) {
        if (context == null) throw new IllegalArgumentException("context == null");
        this.context = context.getApplicationContext();
        this.configStore = configStore == null ? new OpenHouseFilesConfigStore(context) : configStore;
        this.httpClient = new OkHttpClient();
    }

    public List<OpenHouseFileSpaceEntry> loadEntries() {
        List<OpenHouseFileSpaceEntry> entries = new ArrayList<>();
        entries.addAll(buildBuiltInLocalEntries(TermuxConstants.TERMUX_HOME_DIR, Environment.getExternalStorageDirectory()));
        addSafEntries(entries);
        addWebDavEntries(entries);
        addS3Entries(entries);
        return entries;
    }

    static List<OpenHouseFileSpaceEntry> buildBuiltInLocalEntries(File termuxHomeDir, File androidSharedDir) {
        List<OpenHouseFileSpaceEntry> entries = new ArrayList<>();
        OpenHouseWorkspacePaths paths = OpenHouseWorkspacePaths.forTermuxHome(termuxHomeDir);
        entries.add(local(
            SPACE_OPENHOUSE_WORKSPACE,
            FileSpaceType.OPENHOUSE_WORKSPACE,
            "OpenHouse workspace",
            "Termux: " + paths.getTermuxWorkspaceDir().getAbsolutePath(),
            paths.getTermuxWorkspaceDir()));
        entries.add(local(
            SPACE_TERMUX_HOME,
            FileSpaceType.TERMUX,
            "Termux Home",
            termuxHomeDir.getAbsolutePath(),
            termuxHomeDir));
        entries.add(local(
            SPACE_UBUNTU_ROOT,
            FileSpaceType.UBUNTU,
            "Ubuntu root",
            paths.getSubdir(OpenHouseWorkspacePaths.DIR_UBUNTU).getAbsolutePath() + "/root",
            new File(paths.getSubdir(OpenHouseWorkspacePaths.DIR_UBUNTU), "root")));
        entries.add(local(
            SPACE_UBUNTU_WORKSPACE,
            FileSpaceType.UBUNTU,
            "Ubuntu workspace",
            paths.getSubdir(OpenHouseWorkspacePaths.DIR_UBUNTU).getAbsolutePath() + "/workspace",
            new File(paths.getSubdir(OpenHouseWorkspacePaths.DIR_UBUNTU), "workspace")));
        if (androidSharedDir != null) {
            entries.add(local(
                SPACE_ANDROID_SHARED,
                FileSpaceType.ANDROID_SHARED,
                "Android shared storage",
                androidSharedDir.getAbsolutePath(),
                androidSharedDir));
        }
        return entries;
    }

    private void addSafEntries(List<OpenHouseFileSpaceEntry> entries) {
        for (OpenHouseFilesConfigStore.SafContainerRecord record : configStore.getSafContainers()) {
            try {
                SafFileSpaceConfig config = new SafFileSpaceConfig(record.id, record.displayName, Uri.parse(record.treeUri));
                entries.add(new OpenHouseFileSpaceEntry(new SafDocumentRepository(context, config)));
            } catch (Exception ignored) {
            }
        }
    }

    private void addWebDavEntries(List<OpenHouseFileSpaceEntry> entries) {
        for (OpenHouseFilesConfigStore.WebDavRecord record : configStore.getWebDavRecords()) {
            try {
                WebDavConfig config = new WebDavConfig(record.id, record.displayName,
                    record.baseUrl, record.username, record.password);
                entries.add(new OpenHouseFileSpaceEntry(new WebDavFileRepository(httpClient, config)));
            } catch (Exception ignored) {
            }
        }
    }

    private void addS3Entries(List<OpenHouseFileSpaceEntry> entries) {
        for (OpenHouseFilesConfigStore.S3Record record : configStore.getS3Records()) {
            try {
                S3ObjectStoreConfig config = new S3ObjectStoreConfig(
                    record.id,
                    record.displayName,
                    record.endpoint,
                    record.region,
                    record.bucket,
                    record.accessKey,
                    record.secretKey,
                    record.sessionToken,
                    record.pathStyleAccess);
                entries.add(new OpenHouseFileSpaceEntry(new S3FileRepository(httpClient, config)));
            } catch (Exception ignored) {
            }
        }
    }

    private static OpenHouseFileSpaceEntry local(String id, FileSpaceType type, String displayName,
                                                String summary, File root) {
        FileSpace space = FileSpace.builder(id, type, displayName)
            .rootLabel(displayName)
            .locationSummary(summary)
            .supportedOperations(EnumSet.allOf(FileOperation.class))
            .metadata("path", root.getAbsolutePath())
            .build();
        FileRepository repository = new LocalFileRepository(space, root);
        return new OpenHouseFileSpaceEntry(repository);
    }
}
