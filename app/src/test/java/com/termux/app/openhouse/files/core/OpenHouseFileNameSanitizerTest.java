package com.termux.app.openhouse.files.core;

import org.junit.Assert;
import org.junit.Test;

public class OpenHouseFileNameSanitizerTest {

    @Test
    public void removesPathSegmentsAndUnsafeCharacters() {
        Assert.assertEquals(
            "report_final_.md",
            OpenHouseFileNameSanitizer.sanitize("../nested/report:final?.md")
        );
    }

    @Test
    public void fallsBackForEmptyOrDotNames() {
        Assert.assertEquals("shared-file", OpenHouseFileNameSanitizer.sanitize(".."));
        Assert.assertEquals("fallback.txt", OpenHouseFileNameSanitizer.sanitize("", "fallback.txt"));
    }

    @Test
    public void limitsLongNamesButKeepsShortExtension() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 160; i++) {
            name.append('a');
        }
        name.append(".md");

        String sanitized = OpenHouseFileNameSanitizer.sanitize(name.toString());

        Assert.assertEquals(120, sanitized.length());
        Assert.assertTrue(sanitized.endsWith(".md"));
    }
}
