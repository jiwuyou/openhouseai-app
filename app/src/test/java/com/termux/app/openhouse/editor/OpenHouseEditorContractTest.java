package com.termux.app.openhouse.editor;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

@RunWith(RobolectricTestRunner.class)
public class OpenHouseEditorContractTest {

    @Test
    public void localPathIntentSetsStableEditorFields() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File file = new File(context.getCacheDir(), "config.toml");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write("title = \"demo\"".getBytes(StandardCharsets.UTF_8));
        }

        Intent intent = OpenHouseEditorContract.createOpenPathIntent(context, file, "text/plain", true);

        assertEditorComponent(intent);
        Assert.assertEquals(OpenHouseEditorContract.ACTION_OPEN, intent.getAction());
        Assert.assertEquals("text/plain", intent.getType());
        Assert.assertNull(intent.getData());
        Assert.assertEquals(file.getAbsolutePath(), intent.getStringExtra(OpenHouseEditorContract.EXTRA_FILE_PATH));
        Assert.assertEquals("config.toml", intent.getStringExtra(OpenHouseEditorContract.EXTRA_DISPLAY_NAME));
        Assert.assertEquals(file.length(), intent.getLongExtra(OpenHouseEditorContract.EXTRA_SIZE_BYTES, -1L));
        Assert.assertTrue(intent.getBooleanExtra(OpenHouseEditorContract.EXTRA_READ_ONLY, false));
    }

    @Test
    public void contentUriIntentSetsDataTypeFlagsAndReadOnly() {
        Context context = RuntimeEnvironment.getApplication();
        Uri uri = Uri.parse("content://com.example.documents/document/readme.md?token=secret");

        Intent intent = OpenHouseEditorContract.createOpenUriIntent(context, uri, "README.md", "text/markdown", true);

        assertEditorComponent(intent);
        Assert.assertEquals(OpenHouseEditorContract.ACTION_OPEN, intent.getAction());
        Assert.assertEquals(uri, intent.getData());
        Assert.assertEquals("text/markdown", intent.getType());
        Assert.assertEquals(uri.toString(), intent.getStringExtra(OpenHouseEditorContract.EXTRA_FILE_URI));
        Assert.assertEquals("README.md", intent.getStringExtra(OpenHouseEditorContract.EXTRA_DISPLAY_NAME));
        Assert.assertEquals("text/markdown", intent.getStringExtra(OpenHouseEditorContract.EXTRA_MIME_TYPE));
        Assert.assertTrue(intent.getBooleanExtra(OpenHouseEditorContract.EXTRA_READ_ONLY, false));
        Assert.assertTrue((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        Assert.assertEquals(0, intent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    @Test
    public void repositoryExportIntentKeepsSourceMetadataForEditorOnly() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File file = new File(context.getCacheDir(), "exported.md");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write("# exported".getBytes(StandardCharsets.UTF_8));
        }

        Intent intent = OpenHouseEditorContract.createOpenRepositoryExportIntent(
            context,
            file,
            "remote.md",
            "text/markdown",
            "https://alice:secret@example.com/dav/remote.md?token=hidden",
            "webdav-main",
            "/remote.md?signature=hidden");

        assertEditorComponent(intent);
        Assert.assertEquals(OpenHouseEditorContract.ACTION_OPEN, intent.getAction());
        Assert.assertEquals("text/markdown", intent.getType());
        Assert.assertEquals(file.getAbsolutePath(), intent.getStringExtra(OpenHouseEditorContract.EXTRA_FILE_PATH));
        Assert.assertEquals("remote.md", intent.getStringExtra(OpenHouseEditorContract.EXTRA_DISPLAY_NAME));
        Assert.assertEquals("https://alice:secret@example.com/dav/remote.md?token=hidden",
            intent.getStringExtra(OpenHouseEditorContract.EXTRA_NATIVE_LOCATION));
        Assert.assertEquals("webdav-main", intent.getStringExtra(OpenHouseEditorContract.EXTRA_SOURCE_SPACE_ID));
        Assert.assertEquals("/remote.md?signature=hidden",
            intent.getStringExtra(OpenHouseEditorContract.EXTRA_SOURCE_FILE_ID));
        Assert.assertTrue(intent.getBooleanExtra(OpenHouseEditorContract.EXTRA_REPOSITORY_EXPORT, false));
        Assert.assertFalse(intent.getBooleanExtra(OpenHouseEditorContract.EXTRA_READ_ONLY, true));
    }

    private static void assertEditorComponent(Intent intent) {
        Assert.assertNotNull(intent.getComponent());
        Assert.assertEquals(OpenHouseEditorActivity.class.getName(), intent.getComponent().getClassName());
    }
}
