package com.termux.app.openhouse.files.ui;

import com.termux.app.openhouse.files.model.FileSpaceType;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

public class OpenHouseFileSpaceCatalogTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void builtInLocalEntriesSkipUbuntuWhenRootfsIsMissing() throws Exception {
        File home = temporaryFolder.newFolder("home");
        File androidShared = temporaryFolder.newFolder("shared");

        List<OpenHouseFileSpaceEntry> entries = OpenHouseFileSpaceCatalog.buildBuiltInLocalEntries(home, androidShared);

        Assert.assertEquals(3, entries.size());
        assertSpace(entries, OpenHouseFileSpaceCatalog.SPACE_OPENHOUSE_WORKSPACE, FileSpaceType.OPENHOUSE_WORKSPACE);
        assertSpace(entries, OpenHouseFileSpaceCatalog.SPACE_TERMUX_HOME, FileSpaceType.TERMUX);
        assertSpace(entries, OpenHouseFileSpaceCatalog.SPACE_ANDROID_SHARED, FileSpaceType.ANDROID_SHARED);
    }

    @Test
    public void builtInLocalEntriesUseRealUbuntuRootfsPath() throws Exception {
        File files = temporaryFolder.newFolder("files");
        File home = new File(files, "home");
        Assert.assertTrue(home.mkdirs());
        File androidShared = temporaryFolder.newFolder("shared");
        File ubuntuHome = new File(files, "usr/var/lib/proot-distro/containers/ubuntu/rootfs/root");
        Assert.assertTrue(new File(ubuntuHome, "openhouse/workspace").mkdirs());
        File badWorkspaceRoot = new File(home, "openhouse/workspace/ubuntu/root");
        Assert.assertTrue(badWorkspaceRoot.mkdirs());

        List<OpenHouseFileSpaceEntry> entries = OpenHouseFileSpaceCatalog.buildBuiltInLocalEntries(home, androidShared);

        assertSpace(entries, OpenHouseFileSpaceCatalog.SPACE_UBUNTU_ROOT, FileSpaceType.UBUNTU);
        assertSpace(entries, OpenHouseFileSpaceCatalog.SPACE_UBUNTU_WORKSPACE, FileSpaceType.UBUNTU);
        Assert.assertEquals(ubuntuHome.getAbsolutePath(),
            find(entries, OpenHouseFileSpaceCatalog.SPACE_UBUNTU_ROOT).getRepository().getSpace().getLocationSummary());
    }

    @Test
    public void builtInLocalEntriesAcceptLegacyUbuntuRootSymlink() throws Exception {
        File files = temporaryFolder.newFolder("files");
        File home = new File(files, "home");
        Assert.assertTrue(home.mkdirs());
        File ubuntuHome = new File(files, "usr/var/lib/proot-distro/containers/ubuntu/rootfs/root");
        Assert.assertTrue(new File(ubuntuHome, "openhouse/workspace").mkdirs());
        try {
            Files.createSymbolicLink(new File(home, "ubuntu-root").toPath(), ubuntuHome.toPath());
        } catch (UnsupportedOperationException | SecurityException e) {
            return;
        }

        List<OpenHouseFileSpaceEntry> entries = OpenHouseFileSpaceCatalog.buildBuiltInLocalEntries(home, null);

        assertSpace(entries, OpenHouseFileSpaceCatalog.SPACE_UBUNTU_ROOT, FileSpaceType.UBUNTU);
        assertSpace(entries, OpenHouseFileSpaceCatalog.SPACE_UBUNTU_WORKSPACE, FileSpaceType.UBUNTU);
    }

    private static void assertSpace(List<OpenHouseFileSpaceEntry> entries, String id, FileSpaceType type) {
        OpenHouseFileSpaceEntry entry = find(entries, id);
        Assert.assertEquals(type, entry.getRepository().getSpace().getType());
        Assert.assertFalse(entry.getDisplayName().trim().isEmpty());
    }

    private static OpenHouseFileSpaceEntry find(List<OpenHouseFileSpaceEntry> entries, String id) {
        for (OpenHouseFileSpaceEntry entry : entries) {
            if (id.equals(entry.getId())) {
                return entry;
            }
        }
        throw new AssertionError("Missing space " + id);
    }
}
