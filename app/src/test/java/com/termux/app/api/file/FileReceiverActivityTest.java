package com.termux.app.api.file;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.termux.app.TermuxService;
import com.termux.app.api.file.FileReceiverActivity;
import com.termux.app.openhouse.files.core.OpenHouseWorkspacePaths;
import com.termux.app.openhouse.files.importing.OpenHouseFileImportManager;
import com.termux.app.openhouse.files.importing.OpenHouseImportIntents;
import com.termux.app.openhouse.files.importing.OpenHouseImportSource;
import com.termux.app.openhouse.files.importing.OpenHouseImportSources;
import com.termux.app.openhouse.files.importing.OpenHouseImportedFile;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class FileReceiverActivityTest {

    @Test
    public void testIsSharedTextAnUrl() {
        List<String> validUrls = new ArrayList<>();
        validUrls.add("http://example.com");
        validUrls.add("https://example.com");
        validUrls.add("https://example.com/path/parameter=foo");
        validUrls.add("magnet:?xt=urn:btih:d540fc48eb12f2833163eed6421d449dd8f1ce1f&dn=Ubuntu+desktop+19.04+%2864bit%29&tr=udp%3A%2F%2Ftracker.openbittorrent.com%3A80&tr=udp%3A%2F%2Ftracker.publicbt.com%3A80&tr=udp%3A%2F%2Ftracker.ccc.de%3A80");
        for (String url : validUrls) {
            Assert.assertTrue(FileReceiverActivity.isSharedTextAnUrl(url));
        }

        List<String> invalidUrls = new ArrayList<>();
        invalidUrls.add("a test with example.com");
        invalidUrls.add("");
        invalidUrls.add(null);
        for (String url : invalidUrls) {
            Assert.assertFalse(FileReceiverActivity.isSharedTextAnUrl(url));
        }
    }

    @Test
    public void sharedTextImportSourceBuildsStableFileNameAndMetadata() {
        OpenHouseImportSource source = OpenHouseImportSources.forSharedText(
            "hello",
            "meeting notes",
            null
        );

        Assert.assertEquals("meeting notes.txt", source.getSuggestedFileName());
        Assert.assertEquals("text/plain", source.getMimeType());
        Assert.assertEquals("hello".getBytes(StandardCharsets.UTF_8).length, source.getSizeBytes());
        Assert.assertEquals("Android share sheet text payload", source.getAndroidDisplayLocation());

        Assert.assertEquals(
            "already.md",
            OpenHouseImportSources.forSharedText("hello", "already.md", "text/markdown").getSuggestedFileName()
        );
        Assert.assertEquals(
            "shared-text.txt",
            OpenHouseImportSources.forSharedText("hello", null, null).getSuggestedFileName()
        );
    }

    @Test
    public void contentUriMetadataFallbacksPreferDisplayNameThenSubjectThenUriBasename() {
        Assert.assertEquals(
            "display.pdf",
            OpenHouseImportSources.chooseContentFileName("display.pdf", "subject.txt", "uri.bin")
        );
        Assert.assertEquals(
            "subject.txt",
            OpenHouseImportSources.chooseContentFileName("", "subject.txt", "uri.bin")
        );
        Assert.assertEquals(
            "uri.bin",
            OpenHouseImportSources.chooseContentFileName(null, " ", "uri.bin")
        );

        Assert.assertEquals(
            "com.example.documents / uri.bin",
            OpenHouseImportSources.buildAndroidDisplayLocation(
                Uri.parse("content://com.example.documents/tree/root/document/uri.bin"),
                "uri.bin"
            )
        );
        Assert.assertEquals(
            "Android content provider / fallback.txt",
            OpenHouseImportSources.buildAndroidDisplayLocation((String) null, "fallback.txt")
        );
    }

    @Test
    public void aiDescriptionIntentContainsTextPayload() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        OpenHouseImportedFile importedFile = importTestFile(context, "notes.md", "text/markdown");

        Intent intent = OpenHouseImportIntents.createShareAiDescriptionIntent(importedFile);

        Assert.assertEquals(Intent.ACTION_SEND, intent.getAction());
        Assert.assertEquals("text/plain", intent.getType());
        Assert.assertEquals("OpenHouse 文件说明: notes.md", intent.getStringExtra(Intent.EXTRA_SUBJECT));
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        Assert.assertNotNull(text);
        Assert.assertTrue(text.contains("openhouse/workspace/inbox/notes.md"));
        Assert.assertTrue(text.contains("/root/openhouse/workspace/inbox/notes.md"));
    }

    @Test
    public void openExternallyIntentUsesInboxFileProviderUri() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        OpenHouseImportedFile importedFile = importTestFile(context, "open-me.md", "text/markdown");

        Intent intent = OpenHouseImportIntents.createOpenExternallyIntent(context, importedFile);

        Assert.assertEquals(Intent.ACTION_VIEW, intent.getAction());
        Assert.assertEquals("text/markdown", intent.getType());
        Assert.assertTrue((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        Assert.assertNotNull(intent.getData());
        Assert.assertEquals("content", intent.getData().getScheme());
        Assert.assertTrue(intent.getData().toString().contains("openhouse_inbox"));
        Assert.assertEquals(intent.getData(), intent.getParcelableExtra(Intent.EXTRA_STREAM));
    }

    @Test
    public void legacyTermuxCompatibilityIntentsTargetTermuxService() {
        File file = new File("/data/data/com.termux/files/home/openhouse/workspace/inbox/notes.md");

        Intent editorIntent = OpenHouseImportIntents.createLegacyEditorServiceIntent(
            "com.termux",
            TermuxService.class,
            "/data/data/com.termux/files/home/bin/termux-file-editor",
            file
        );

        Assert.assertEquals(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, editorIntent.getAction());
        Assert.assertNotNull(editorIntent.getComponent());
        Assert.assertEquals("com.termux", editorIntent.getComponent().getPackageName());
        Assert.assertEquals(TermuxService.class.getName(), editorIntent.getComponent().getClassName());
        Assert.assertEquals("file", editorIntent.getData().getScheme());
        Assert.assertArrayEquals(
            new String[]{file.getAbsolutePath()},
            editorIntent.getStringArrayExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS)
        );

        Intent directoryIntent = OpenHouseImportIntents.createLegacyOpenReceiveDirServiceIntent(
            "com.termux",
            TermuxService.class,
            "/data/data/com.termux/files/home/openhouse/workspace/inbox"
        );

        Assert.assertEquals(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, directoryIntent.getAction());
        Assert.assertNotNull(directoryIntent.getComponent());
        Assert.assertEquals("com.termux", directoryIntent.getComponent().getPackageName());
        Assert.assertEquals(
            "/data/data/com.termux/files/home/openhouse/workspace/inbox",
            directoryIntent.getStringExtra(TERMUX_SERVICE.EXTRA_WORKDIR)
        );
    }

    private OpenHouseImportedFile importTestFile(Context context, String fileName, String mimeType) throws Exception {
        File homeDir = new File(context.getFilesDir(), "home");
        deleteRecursively(homeDir);
        OpenHouseWorkspacePaths paths = OpenHouseWorkspacePaths.forTermuxHome(homeDir);
        OpenHouseImportSource source = OpenHouseImportSource.builder()
            .setSuggestedFileName(fileName)
            .setMimeType(mimeType)
            .setAndroidDisplayLocation("test")
            .build();
        return new OpenHouseFileImportManager(paths).importStream(
            new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)),
            source
        );
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

}
