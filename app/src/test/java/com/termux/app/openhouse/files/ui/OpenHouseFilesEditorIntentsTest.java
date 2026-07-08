package com.termux.app.openhouse.files.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.termux.app.openhouse.editor.OpenHouseEditorActivity;
import com.termux.app.openhouse.editor.OpenHouseEditorContract;
import com.termux.app.openhouse.files.model.FileItem;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;

@RunWith(RobolectricTestRunner.class)
public class OpenHouseFilesEditorIntentsTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void localPathIntentUsesEditorContract() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File file = temporaryFolder.newFile("notes.md");
        FileItem item = FileItem.builder("termux-home", "notes.md", "notes.md", false)
            .mimeType("text/markdown")
            .size(12)
            .writable(true)
            .nativeLocation(file.toURI().toString())
            .build();

        Intent intent = OpenHouseFilesEditorIntents.createOpenIntent(context, item, null, "termux-home");

        assertEditorIntent(intent);
        Assert.assertEquals(file.getAbsolutePath(), intent.getStringExtra(OpenHouseEditorContract.EXTRA_FILE_PATH));
        Assert.assertEquals("notes.md", intent.getStringExtra(OpenHouseEditorContract.EXTRA_DISPLAY_NAME));
        Assert.assertEquals("text/markdown", intent.getStringExtra(OpenHouseEditorContract.EXTRA_MIME_TYPE));
        Assert.assertEquals(file.toURI().toString(), intent.getStringExtra(OpenHouseEditorContract.EXTRA_NATIVE_LOCATION));
        Assert.assertEquals("termux-home", intent.getStringExtra(OpenHouseEditorContract.EXTRA_SOURCE_SPACE_ID));
        Assert.assertEquals("notes.md", intent.getStringExtra(OpenHouseEditorContract.EXTRA_SOURCE_FILE_ID));
        Assert.assertFalse(intent.getBooleanExtra(OpenHouseEditorContract.EXTRA_READ_ONLY, true));
        Assert.assertFalse(intent.getBooleanExtra(OpenHouseEditorContract.EXTRA_REPOSITORY_EXPORT, false));
        assertNoFilesPrivateExtras(intent);
    }

    @Test
    public void contentUriIntentUsesEditorContract() {
        Context context = RuntimeEnvironment.getApplication();
        Uri uri = Uri.parse("content://com.example.documents/tree/root/document/root%2Fnote.txt");
        FileItem item = FileItem.builder("saf-space", uri.toString(), "note.txt", false)
            .mimeType("text/plain")
            .size(34)
            .writable(false)
            .nativeLocation(uri.toString())
            .build();

        Intent intent = OpenHouseFilesEditorIntents.createOpenIntent(context, item, null, "saf-space");

        assertEditorIntent(intent);
        Assert.assertEquals(uri, intent.getData());
        Assert.assertEquals(uri.toString(), intent.getStringExtra(OpenHouseEditorContract.EXTRA_FILE_URI));
        Assert.assertEquals("note.txt", intent.getStringExtra(OpenHouseEditorContract.EXTRA_DISPLAY_NAME));
        Assert.assertEquals("text/plain", intent.getStringExtra(OpenHouseEditorContract.EXTRA_MIME_TYPE));
        Assert.assertEquals(uri.toString(), intent.getStringExtra(OpenHouseEditorContract.EXTRA_NATIVE_LOCATION));
        Assert.assertEquals("saf-space", intent.getStringExtra(OpenHouseEditorContract.EXTRA_SOURCE_SPACE_ID));
        Assert.assertEquals(uri.toString(), intent.getStringExtra(OpenHouseEditorContract.EXTRA_SOURCE_FILE_ID));
        Assert.assertTrue(intent.getBooleanExtra(OpenHouseEditorContract.EXTRA_READ_ONLY, false));
        Assert.assertTrue((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        Assert.assertEquals(0, intent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        assertNoFilesPrivateExtras(intent);
    }

    @Test
    public void writableContentUriIntentIncludesWriteGrant() {
        Context context = RuntimeEnvironment.getApplication();
        Uri uri = Uri.parse("content://com.example.documents/tree/root/document/root%2Feditable.txt");
        FileItem item = FileItem.builder("saf-space", uri.toString(), "editable.txt", false)
            .mimeType("text/plain")
            .size(78)
            .writable(true)
            .nativeLocation(uri.toString())
            .build();

        Intent intent = OpenHouseFilesEditorIntents.createOpenIntent(context, item, null, "saf-space");

        assertEditorIntent(intent);
        Assert.assertEquals(uri, intent.getData());
        Assert.assertFalse(intent.getBooleanExtra(OpenHouseEditorContract.EXTRA_READ_ONLY, true));
        Assert.assertTrue((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        Assert.assertTrue((intent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0);
        assertNoFilesPrivateExtras(intent);
    }

    @Test
    public void repositoryExportIntentUsesEditorContract() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File exported = temporaryFolder.newFile("remote.md");
        FileItem item = FileItem.builder("webdav", "docs/remote.md", "Remote.md", false)
            .mimeType("text/markdown")
            .size(56)
            .writable(true)
            .nativeLocation("https://dav.example.com/docs/remote.md")
            .build();

        Intent intent = OpenHouseFilesEditorIntents.createOpenIntent(context, item, exported, "webdav");

        assertEditorIntent(intent);
        Assert.assertEquals(exported.getAbsolutePath(), intent.getStringExtra(OpenHouseEditorContract.EXTRA_FILE_PATH));
        Assert.assertEquals("Remote.md", intent.getStringExtra(OpenHouseEditorContract.EXTRA_DISPLAY_NAME));
        Assert.assertEquals("text/markdown", intent.getStringExtra(OpenHouseEditorContract.EXTRA_MIME_TYPE));
        Assert.assertEquals("https://dav.example.com/docs/remote.md", intent.getStringExtra(OpenHouseEditorContract.EXTRA_NATIVE_LOCATION));
        Assert.assertEquals("webdav", intent.getStringExtra(OpenHouseEditorContract.EXTRA_SOURCE_SPACE_ID));
        Assert.assertEquals("docs/remote.md", intent.getStringExtra(OpenHouseEditorContract.EXTRA_SOURCE_FILE_ID));
        Assert.assertTrue(intent.getBooleanExtra(OpenHouseEditorContract.EXTRA_REPOSITORY_EXPORT, false));
        Assert.assertFalse(intent.getBooleanExtra(OpenHouseEditorContract.EXTRA_READ_ONLY, true));
        assertNoFilesPrivateExtras(intent);
    }

    private static void assertEditorIntent(Intent intent) {
        Assert.assertEquals(OpenHouseEditorContract.ACTION_OPEN, intent.getAction());
        Assert.assertNotNull(intent.getComponent());
        Assert.assertEquals(OpenHouseEditorActivity.class.getName(), intent.getComponent().getClassName());
    }

    private static void assertNoFilesPrivateExtras(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) return;
        for (String key : extras.keySet()) {
            Assert.assertFalse("Unexpected files private extra: " + key,
                key.startsWith("com.termux.app.openhouse.files.extra."));
        }
    }
}
