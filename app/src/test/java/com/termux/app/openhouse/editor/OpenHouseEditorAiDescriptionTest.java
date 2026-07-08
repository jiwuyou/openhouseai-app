package com.termux.app.openhouse.editor;

import org.junit.Assert;
import org.junit.Test;

public class OpenHouseEditorAiDescriptionTest {

    @Test
    public void descriptionIncludesContentUriWarningAndTruncationState() {
        OpenHouseEditorDocument document = OpenHouseEditorDocument.builder()
            .setAndroidUri("content://com.example.documents/document/readme")
            .setDisplayName("README.md")
            .setMimeType("text/markdown")
            .setSizeBytes(2048L)
            .setAndroidDisplayLocation("com.example.documents / README.md")
            .build();
        OpenHouseEditorLoadResult loadResult = OpenHouseEditorLoadResult.loaded(
            "# Head",
            1024L,
            2048L,
            true,
            true,
            "truncated");

        String description = OpenHouseEditorAiDescription.build(document, loadResult);

        Assert.assertTrue(description.contains("content://com.example.documents/document/readme"));
        Assert.assertTrue(description.contains("不是 POSIX 文件路径"));
        Assert.assertTrue(description.contains("内容截断: 是"));
        Assert.assertTrue(description.contains("只读: 是"));
        Assert.assertTrue(description.contains("已加载内容大小: 1.0 KiB"));
    }

    @Test
    public void descriptionKeepsRepositorySourceMetadata() {
        OpenHouseEditorDocument document = OpenHouseEditorDocument.builder()
            .setLocalFile(new java.io.File("/data/data/com.termux/files/home/openhouse/workspace/export/tmp.txt"))
            .setDisplayName("tmp.txt")
            .setMimeType("text/plain")
            .setNativeLocation("s3://bucket/path/tmp.txt")
            .setSourceSpaceId("s3-main")
            .setSourceFileId("path/tmp.txt")
            .build();

        String description = OpenHouseEditorAiDescription.build(
            document,
            OpenHouseEditorLoadResult.loaded("hello", 5L, 5L, false, false, ""));

        Assert.assertTrue(description.contains("来源原生位置: s3://bucket/path/tmp.txt"));
        Assert.assertTrue(description.contains("来源空间: s3-main"));
        Assert.assertTrue(description.contains("来源文件 ID: path/tmp.txt"));
    }

    @Test
    public void descriptionRedactsSensitiveSourceMetadata() {
        OpenHouseEditorDocument document = OpenHouseEditorDocument.builder()
            .setAndroidUri("content://com.example.documents/document/readme.md?token=android-secret")
            .setDisplayName("readme.md")
            .setMimeType("text/markdown")
            .setNativeLocation("https://alice:password@example.com/dav/readme.md?token=secret&X-Amz-Signature=abc#frag")
            .setSourceSpaceId("webdav")
            .setSourceFileId("/dav/readme.md?signature=secret")
            .build();

        String description = OpenHouseEditorAiDescription.build(
            document,
            OpenHouseEditorLoadResult.loaded("# hi", 4L, 4L, false, false, ""));

        Assert.assertTrue(description.contains("content://com.example.documents/document/readme.md"));
        Assert.assertTrue(description.contains("来源原生位置: https://example.com/dav/readme.md"));
        Assert.assertTrue(description.contains("来源文件 ID: /dav/readme.md?[redacted]"));
        Assert.assertFalse(description.contains("android-secret"));
        Assert.assertFalse(description.contains("alice:password"));
        Assert.assertFalse(description.contains("X-Amz-Signature"));
        Assert.assertFalse(description.contains("token=secret"));
        Assert.assertFalse(description.contains("signature=secret"));
    }
}
