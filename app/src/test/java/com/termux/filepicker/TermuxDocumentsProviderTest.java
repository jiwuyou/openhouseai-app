package com.termux.filepicker;

import org.junit.Assert;
import org.junit.Test;

public class TermuxDocumentsProviderTest {

    @Test
    public void documentIdsUseStableRootPrefix() {
        Assert.assertEquals(
            "openhouse-workspace:dir/file.md",
            TermuxDocumentsProvider.buildDocumentId(
                TermuxDocumentsProvider.ROOT_OPENHOUSE_WORKSPACE,
                "dir/file.md"));
        Assert.assertEquals(
            "termux-home:",
            TermuxDocumentsProvider.buildDocumentId(
                TermuxDocumentsProvider.ROOT_TERMUX_HOME,
                ""));
    }

    @Test
    public void documentIdHelpersParseRootAndRelativePath() {
        String docId = TermuxDocumentsProvider.buildDocumentId(
            TermuxDocumentsProvider.ROOT_UBUNTU_ROOT,
            "/root/project/readme.md");

        Assert.assertEquals(TermuxDocumentsProvider.ROOT_UBUNTU_ROOT,
            TermuxDocumentsProvider.getRootIdFromDocumentId(docId));
        Assert.assertEquals("root/project/readme.md",
            TermuxDocumentsProvider.getRelativePathFromDocumentId(docId));
        Assert.assertEquals("", TermuxDocumentsProvider.getRootIdFromDocumentId("missing-separator"));
        Assert.assertEquals("", TermuxDocumentsProvider.getRelativePathFromDocumentId("termux-home:"));
    }

    @Test
    public void documentIdBuilderNormalizesBackslashesAndLeadingSlashes() {
        Assert.assertEquals(
            "termux-home:folder/file.txt",
            TermuxDocumentsProvider.buildDocumentId(
                TermuxDocumentsProvider.ROOT_TERMUX_HOME,
                "\\folder\\file.txt"));
    }
}
