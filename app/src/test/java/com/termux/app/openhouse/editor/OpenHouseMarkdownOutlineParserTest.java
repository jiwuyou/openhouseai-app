package com.termux.app.openhouse.editor;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class OpenHouseMarkdownOutlineParserTest {

    @Test
    public void parsesAtxAndSetextHeadings() {
        String markdown = "# Title\n\nBody\n\nSection\n---\n\n### Deep ###\n";

        List<OpenHouseMarkdownHeading> headings = OpenHouseMarkdownOutlineParser.parse(markdown);

        Assert.assertEquals(3, headings.size());
        Assert.assertEquals(1, headings.get(0).getLevel());
        Assert.assertEquals("Title", headings.get(0).getTitle());
        Assert.assertEquals(1, headings.get(0).getLineNumber());
        Assert.assertEquals(2, headings.get(1).getLevel());
        Assert.assertEquals("Section", headings.get(1).getTitle());
        Assert.assertEquals(5, headings.get(1).getLineNumber());
        Assert.assertEquals(3, headings.get(2).getLevel());
        Assert.assertEquals("Deep", headings.get(2).getTitle());
    }

    @Test
    public void ignoresHeadingsInsideFencedCode() {
        String markdown = "# Real\n\n```sh\n# Not a heading\n```\n\n## Also real\n";

        List<OpenHouseMarkdownHeading> headings = OpenHouseMarkdownOutlineParser.parse(markdown);

        Assert.assertEquals(2, headings.size());
        Assert.assertEquals("Real", headings.get(0).getTitle());
        Assert.assertEquals("Also real", headings.get(1).getTitle());
    }
}
