package com.termux.app.openhouse.files.network;

import com.termux.app.openhouse.files.model.FileItem;
import com.termux.app.openhouse.files.model.FileOperationException;

import org.junit.Assert;
import org.junit.Test;

public class NetworkRepositoryUtilsTest {

    @Test
    public void normalizesOnlyStrictRelativeIds() throws Exception {
        Assert.assertEquals(FileItem.ROOT_ID, NetworkRepositoryUtils.normalizeObjectId(""));
        Assert.assertEquals(FileItem.ROOT_ID, NetworkRepositoryUtils.normalizeObjectId("/"));
        Assert.assertEquals("folder/report.md", NetworkRepositoryUtils.normalizeObjectId("folder/report.md"));
        Assert.assertEquals("folder/", NetworkRepositoryUtils.normalizeDirectoryId("folder"));
        Assert.assertEquals("folder/", NetworkRepositoryUtils.normalizeObjectId("folder/"));
    }

    @Test
    public void rejectsTraversalAndAmbiguousSegments() throws Exception {
        assertInvalid("../secret.txt");
        assertInvalid("folder/../secret.txt");
        assertInvalid("folder/./secret.txt");
        assertInvalid("folder//secret.txt");
        assertInvalid("folder\\..\\secret.txt");
        assertInvalid("/absolute/path.txt");
    }

    private static void assertInvalid(String id) throws Exception {
        try {
            NetworkRepositoryUtils.normalizeObjectId(id);
            Assert.fail("Expected invalid path for " + id);
        } catch (FileOperationException e) {
            Assert.assertEquals(FileOperationException.Code.INVALID_PATH, e.getCode());
        }
    }
}
