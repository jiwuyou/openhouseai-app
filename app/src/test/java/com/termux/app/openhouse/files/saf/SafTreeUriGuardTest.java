package com.termux.app.openhouse.files.saf;

import android.net.Uri;
import android.provider.DocumentsContract;

import com.termux.app.openhouse.files.model.FileOperationException;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class SafTreeUriGuardTest {

    @Test
    public void acceptsDocumentUriInsideConfiguredTree() throws Exception {
        Uri tree = treeUri("com.example.documents", "primary:OpenHouse");
        Uri child = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:OpenHouse/notes/report.md");

        SafTreeUriGuard.validateDocumentUri(tree, child);

        Assert.assertEquals(child, SafTreeUriGuard.uriForId(tree, child.toString()));
    }

    @Test
    public void rootIdBuildsRootDocumentUri() throws Exception {
        Uri tree = treeUri("com.example.documents", "primary:OpenHouse");

        Uri root = SafTreeUriGuard.uriForId(tree, "/");

        Assert.assertEquals("primary:OpenHouse", DocumentsContract.getDocumentId(root));
    }

    @Test
    public void acceptsProviderRootColonDocumentIdsWithoutSlash() throws Exception {
        Uri tree = treeUri("com.termux.documents", "ubuntu-root:");
        Uri child = DocumentsContract.buildDocumentUriUsingTree(tree, "ubuntu-root:.aionui-web");

        SafTreeUriGuard.validateDocumentUri(tree, child);

        Assert.assertTrue(SafTreeUriGuard.isDocumentIdInsideTree("ubuntu-root:", "ubuntu-root:.aionui-web"));
    }

    @Test
    public void rejectsCrossTreeAndCrossProviderDocumentUris() throws Exception {
        Uri tree = treeUri("com.example.documents", "primary:OpenHouse");
        assertInvalid(tree, DocumentsContract.buildDocumentUriUsingTree(
            treeUri("com.other.documents", "primary:OpenHouse"),
            "primary:OpenHouse/notes/report.md"));
        assertInvalid(tree, DocumentsContract.buildDocumentUriUsingTree(
            treeUri("com.example.documents", "primary:Other"),
            "primary:Other/notes/report.md"));
        assertInvalid(tree, DocumentsContract.buildDocumentUriUsingTree(
            tree,
            "primary:Other/notes/report.md"));
        assertInvalid(treeUri("com.termux.documents", "ubuntu-root:"),
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri("com.termux.documents", "ubuntu-root:"),
                "termux-home:.ssh/config"));
        assertInvalid(treeUri("com.termux.documents", "ubuntu-root:"),
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri("com.termux.documents", "ubuntu-root:"),
                "ubuntu-rootfs:.ssh/config"));
    }

    @Test
    public void rejectsDotSegmentsInsideDocumentId() throws Exception {
        Uri tree = treeUri("com.example.documents", "primary:OpenHouse");
        Uri child = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:OpenHouse/../secret.md");

        assertInvalid(tree, child);
    }

    private static Uri treeUri(String authority, String treeDocumentId) {
        return DocumentsContract.buildTreeDocumentUri(authority, treeDocumentId);
    }

    private static void assertInvalid(Uri tree, Uri document) throws Exception {
        try {
            SafTreeUriGuard.validateDocumentUri(tree, document);
            Assert.fail("Expected invalid SAF uri: " + document);
        } catch (FileOperationException e) {
            Assert.assertEquals(FileOperationException.Code.INVALID_PATH, e.getCode());
        }
    }
}
