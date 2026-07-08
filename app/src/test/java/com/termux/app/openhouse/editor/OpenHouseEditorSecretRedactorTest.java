package com.termux.app.openhouse.editor;

import org.junit.Assert;
import org.junit.Test;

public class OpenHouseEditorSecretRedactorTest {

    @Test
    public void redactsWebDavUserInfoQueryAndFragment() {
        String redacted = OpenHouseEditorSecretRedactor.redact(
            "https://alice:secret@example.com/dav/notes.md?password=hidden&token=abc#section");

        Assert.assertEquals("https://example.com/dav/notes.md", redacted);
        Assert.assertFalse(redacted.contains("alice"));
        Assert.assertFalse(redacted.contains("secret"));
        Assert.assertFalse(redacted.contains("token"));
        Assert.assertFalse(redacted.contains("password"));
    }

    @Test
    public void redactsS3PresignedUrlQuery() {
        String redacted = OpenHouseEditorSecretRedactor.redact(
            "https://bucket.s3.example.com/path/file.md?X-Amz-Credential=AKIA%2Fscope&X-Amz-Signature=abcdef&access_key=secret");

        Assert.assertEquals("https://bucket.s3.example.com/path/file.md", redacted);
        Assert.assertFalse(redacted.contains("X-Amz"));
        Assert.assertFalse(redacted.contains("Signature"));
        Assert.assertFalse(redacted.contains("access_key"));
        Assert.assertFalse(redacted.contains("abcdef"));
    }

    @Test
    public void redactsContentUriQuery() {
        String redacted = OpenHouseEditorSecretRedactor.redact(
            "content://com.example.documents/document/readme.md?token=abc&signature=def#frag");

        Assert.assertEquals("content://com.example.documents/document/readme.md", redacted);
        Assert.assertFalse(redacted.contains("token"));
        Assert.assertFalse(redacted.contains("signature"));
    }

    @Test
    public void keepsPlainLocalPathReadable() {
        String path = "/data/data/com.termux/files/home/openhouse/workspace/inbox/README.md";

        Assert.assertEquals(path, OpenHouseEditorSecretRedactor.redact(path));
    }
}
