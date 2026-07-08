package com.termux.app.openhouse.editor;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.Arrays;

public class OpenHouseEditorTextReaderTest {

    @Test
    public void oneMiBLimitTruncatesAndMarksReadOnly() throws Exception {
        int maxBytes = (int) OpenHouseEditorActivity.MAX_EDITABLE_BYTES;
        byte[] bytes = new byte[maxBytes + 128];
        Arrays.fill(bytes, (byte) 'a');

        OpenHouseEditorLoadResult result = OpenHouseEditorTextReader.readUtf8(
            new ByteArrayInputStream(bytes),
            bytes.length,
            OpenHouseEditorActivity.MAX_EDITABLE_BYTES);

        Assert.assertEquals(maxBytes, result.getLoadedBytes());
        Assert.assertEquals(maxBytes, result.getText().length());
        Assert.assertEquals(bytes.length, result.getSourceSizeBytes());
        Assert.assertTrue(result.isContentTruncated());
        Assert.assertTrue(result.isReadOnly());
    }
}
