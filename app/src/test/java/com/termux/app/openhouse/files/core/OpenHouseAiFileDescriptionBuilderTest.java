package com.termux.app.openhouse.files.core;

import org.junit.Assert;
import org.junit.Test;

public class OpenHouseAiFileDescriptionBuilderTest {

    @Test
    public void contentUriDescriptionDoesNotPretendToBePosixPath() {
        OpenHouseFileReference reference = OpenHouseFileReference.builder()
            .setDisplayName("photo.jpg")
            .setMimeType("image/jpeg")
            .setSizeBytes(2048)
            .setAndroidUri("content://com.example.documents/document/photo")
            .setAndroidDisplayLocation("com.example.documents / photo.jpg")
            .setTermuxPath("/data/data/com.termux/files/home/openhouse/workspace/inbox/photo.jpg")
            .setUbuntuPath("/root/openhouse/workspace/inbox/photo.jpg")
            .setWorkspacePath("openhouse/workspace/inbox/photo.jpg")
            .build();

        String description = OpenHouseAiFileDescriptionBuilder.build(reference);

        Assert.assertTrue(description.contains("content://com.example.documents/document/photo"));
        Assert.assertTrue(description.contains("不是 POSIX 文件路径"));
        Assert.assertTrue(description.contains("/data/data/com.termux/files/home/openhouse/workspace/inbox/photo.jpg"));
        Assert.assertTrue(description.contains("/root/openhouse/workspace/inbox/photo.jpg"));
        Assert.assertTrue(description.contains("openhouse/workspace/inbox/photo.jpg"));
    }

    @Test
    public void regularFileUriDoesNotAddContentUriWarning() {
        OpenHouseFileReference reference = OpenHouseFileReference.builder()
            .setDisplayName("notes.txt")
            .setAndroidUri("file:///sdcard/Download/notes.txt")
            .build();

        String description = OpenHouseAiFileDescriptionBuilder.build(reference);

        Assert.assertFalse(description.contains("content:// 是 Android 内容提供者授权"));
    }
}
