package com.termux.app.openhouse.editor;

import org.junit.Assert;
import org.junit.Test;

public class OpenHouseEditorTextTypesTest {

    @Test
    public void treatsCodeConfigAndMarkdownAsText() {
        Assert.assertTrue(OpenHouseEditorTextTypes.isLikelyText("README.md", "application/octet-stream"));
        Assert.assertTrue(OpenHouseEditorTextTypes.isLikelyText("settings.toml", null));
        Assert.assertTrue(OpenHouseEditorTextTypes.isLikelyText("Dockerfile", null));
        Assert.assertTrue(OpenHouseEditorTextTypes.isLikelyText("script.sh", "application/x-sh"));
        Assert.assertTrue(OpenHouseEditorTextTypes.isMarkdown("notes.markdown", null));
    }

    @Test
    public void rejectsCommonBinaryTypesWithoutTextExtension() {
        Assert.assertFalse(OpenHouseEditorTextTypes.isLikelyText("photo.jpg", "image/jpeg"));
        Assert.assertFalse(OpenHouseEditorTextTypes.isLikelyText("archive.zip", "application/zip"));
        Assert.assertFalse(OpenHouseEditorTextTypes.isMarkdown("notes.txt", "text/plain"));
    }
}
