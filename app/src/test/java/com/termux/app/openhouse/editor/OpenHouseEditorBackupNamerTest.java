package com.termux.app.openhouse.editor;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;

public class OpenHouseEditorBackupNamerTest {

    @Test
    public void buildsStableSafeBackupName() {
        String backupName = OpenHouseEditorBackupNamer.buildBackupFileName("../bad:name?.md", 0L);

        Assert.assertEquals(".._bad_name_.md.19700101-000000-000.bak", backupName);
        Assert.assertFalse(backupName.contains("/"));
        Assert.assertFalse(backupName.contains(":"));
    }

    @Test
    public void returnsBackupUnderProvidedRoot() {
        File root = new File("/tmp/openhouse-backups");

        File backup = OpenHouseEditorBackupNamer.buildBackupFile(root, "config.json", 0L);

        Assert.assertEquals(new File(root, "config.json.19700101-000000-000.bak"), backup);
    }
}
