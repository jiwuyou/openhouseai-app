package com.termux.app.openhouse.files.storage;

import com.termux.app.openhouse.files.model.FileItem;
import com.termux.app.openhouse.files.model.FileOperationException;
import com.termux.app.openhouse.files.model.FileSpace;
import com.termux.app.openhouse.files.model.FileSpaceType;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class LocalFileRepositoryTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void uploadListDownloadRenameAndDelete() throws Exception {
        LocalFileRepository repository = repository();

        FileItem uploaded = repository.upload(FileItem.ROOT_ID, "note.txt",
            new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)),
            5,
            "text/plain");

        Assert.assertEquals("note.txt", uploaded.getId());
        List<FileItem> items = repository.list(FileItem.ROOT_ID);
        Assert.assertEquals(1, items.size());
        Assert.assertEquals("note.txt", items.get(0).getDisplayName());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        repository.download("note.txt", output);
        Assert.assertEquals("hello", new String(output.toByteArray(), StandardCharsets.UTF_8));

        FileItem renamed = repository.rename("note.txt", "renamed.txt");
        Assert.assertEquals("renamed.txt", renamed.getId());
        repository.delete("renamed.txt");
        Assert.assertTrue(repository.list(FileItem.ROOT_ID).isEmpty());
    }

    @Test
    public void rejectsParentTraversal() throws Exception {
        LocalFileRepository repository = repository();
        try {
            repository.openInputStream("../secret.txt");
            Assert.fail("Expected invalid path");
        } catch (FileOperationException e) {
            Assert.assertEquals(FileOperationException.Code.INVALID_PATH, e.getCode());
        }
    }

    @Test
    public void deleteDirectorySymlinkDoesNotDeleteTargetDirectory() throws Exception {
        File root = temporaryFolder.newFolder("root");
        File target = temporaryFolder.newFolder("target");
        File targetFile = new File(target, "keep.txt");
        Assert.assertTrue(targetFile.createNewFile());
        File link = new File(root, "linked");
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath());
        } catch (UnsupportedOperationException | SecurityException e) {
            return;
        }
        LocalFileRepository repository = new LocalFileRepository(space(), root);

        repository.delete("linked");

        Assert.assertFalse(link.exists());
        Assert.assertTrue(targetFile.exists());
    }

    @Test
    public void symlinkChildMutationsAreRejectedButReadIsAllowed() throws Exception {
        File root = temporaryFolder.newFolder("root");
        File target = temporaryFolder.newFolder("target");
        File targetFile = new File(target, "keep.txt");
        try (FileOutputStream output = new FileOutputStream(targetFile)) {
            output.write("keep".getBytes(StandardCharsets.UTF_8));
        }
        File link = new File(root, "linked");
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath());
        } catch (UnsupportedOperationException | SecurityException e) {
            return;
        }
        LocalFileRepository repository = new LocalFileRepository(space(), root);

        ByteArrayOutputStream read = new ByteArrayOutputStream();
        repository.download("linked/keep.txt", read);
        Assert.assertEquals("keep", new String(read.toByteArray(), StandardCharsets.UTF_8));

        assertPermissionDenied(() -> repository.delete("linked/keep.txt"));
        assertPermissionDenied(() -> repository.rename("linked/keep.txt", "renamed.txt"));
        assertPermissionDenied(() -> repository.createDirectory("linked", "newdir"));
        assertPermissionDenied(() -> {
            try (OutputStream output = repository.openOutputStream("linked/new.txt", "text/plain")) {
                output.write("bad".getBytes(StandardCharsets.UTF_8));
            }
        });

        Assert.assertTrue(targetFile.exists());
        Assert.assertFalse(new File(target, "new.txt").exists());
        Assert.assertFalse(new File(target, "newdir").exists());
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void assertPermissionDenied(ThrowingRunnable runnable) throws Exception {
        try {
            runnable.run();
            Assert.fail("Expected permission denied");
        } catch (FileOperationException e) {
            Assert.assertEquals(FileOperationException.Code.PERMISSION_DENIED, e.getCode());
        }
    }

    private LocalFileRepository repository() throws Exception {
        return new LocalFileRepository(space(), temporaryFolder.newFolder("root"));
    }

    private FileSpace space() {
        return FileSpace.builder("local", FileSpaceType.LOCAL, "Local")
            .rootLabel("Local")
            .build();
    }
}
