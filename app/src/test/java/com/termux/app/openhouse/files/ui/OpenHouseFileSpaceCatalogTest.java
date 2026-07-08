package com.termux.app.openhouse.files.ui;

import com.termux.app.openhouse.files.model.FileSpaceType;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

public class OpenHouseFileSpaceCatalogTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void builtInLocalEntriesCoverOpenHouseTermuxUbuntuAndAndroid() throws Exception {
        File home = temporaryFolder.newFolder("home");
        File androidShared = temporaryFolder.newFolder("shared");

        List<OpenHouseFileSpaceEntry> entries = OpenHouseFileSpaceCatalog.buildBuiltInLocalEntries(home, androidShared);

        Assert.assertEquals(5, entries.size());
        assertSpace(entries, OpenHouseFileSpaceCatalog.SPACE_OPENHOUSE_WORKSPACE, FileSpaceType.OPENHOUSE_WORKSPACE);
        assertSpace(entries, OpenHouseFileSpaceCatalog.SPACE_TERMUX_HOME, FileSpaceType.TERMUX);
        assertSpace(entries, OpenHouseFileSpaceCatalog.SPACE_UBUNTU_ROOT, FileSpaceType.UBUNTU);
        assertSpace(entries, OpenHouseFileSpaceCatalog.SPACE_UBUNTU_WORKSPACE, FileSpaceType.UBUNTU);
        assertSpace(entries, OpenHouseFileSpaceCatalog.SPACE_ANDROID_SHARED, FileSpaceType.ANDROID_SHARED);
    }

    private static void assertSpace(List<OpenHouseFileSpaceEntry> entries, String id, FileSpaceType type) {
        for (OpenHouseFileSpaceEntry entry : entries) {
            if (id.equals(entry.getId())) {
                Assert.assertEquals(type, entry.getRepository().getSpace().getType());
                Assert.assertFalse(entry.getDisplayName().trim().isEmpty());
                return;
            }
        }
        Assert.fail("Missing space " + id);
    }
}
