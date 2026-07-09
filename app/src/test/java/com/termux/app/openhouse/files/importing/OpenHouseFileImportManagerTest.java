package com.termux.app.openhouse.files.importing;

import com.termux.app.openhouse.files.core.OpenHouseWorkspacePaths;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

public class OpenHouseFileImportManagerTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void defaultImportUsesMonthDirectory() throws Exception {
        OpenHouseWorkspacePaths paths = OpenHouseWorkspacePaths.forTermuxHome(temporaryFolder.newFolder("home"));
        OpenHouseFileImportManager manager = new OpenHouseFileImportManager(
            paths,
            OpenHouseInboxGrouping.DEFAULT,
            fixedDateProvider());

        OpenHouseImportedFile importedFile = manager.importStream(stream("hello"), source("report.md"));

        Assert.assertEquals("inbox/2026-07/report.md", paths.getWorkspaceRelativePath(importedFile.getFile()));
        Assert.assertTrue(importedFile.getFile().isFile());
    }

    @Test
    public void groupingChoicesUseExpectedDirectories() throws Exception {
        assertImportsTo(OpenHouseInboxGrouping.NONE, "inbox/plain.txt");
        assertImportsTo(OpenHouseInboxGrouping.DAY, "inbox/2026-07-09/plain.txt");
        assertImportsTo(OpenHouseInboxGrouping.MONTH, "inbox/2026-07/plain.txt");
        assertImportsTo(OpenHouseInboxGrouping.YEAR, "inbox/2026/plain.txt");
    }

    @Test
    public void nullPerImportGroupingFallsBackToMonth() throws Exception {
        OpenHouseWorkspacePaths paths = OpenHouseWorkspacePaths.forTermuxHome(temporaryFolder.newFolder("home"));
        OpenHouseFileImportManager manager = new OpenHouseFileImportManager(
            paths,
            OpenHouseInboxGrouping.NONE,
            fixedDateProvider());

        OpenHouseImportedFile importedFile = manager.importStream(stream("hello"), source("fallback.md"), null);

        Assert.assertEquals("inbox/2026-07/fallback.md", paths.getWorkspaceRelativePath(importedFile.getFile()));
    }

    @Test
    public void fileNamesAreSanitizedAndConflictsStayInsideGroupingDirectory() throws Exception {
        OpenHouseWorkspacePaths paths = OpenHouseWorkspacePaths.forTermuxHome(temporaryFolder.newFolder("home"));
        OpenHouseFileImportManager manager = new OpenHouseFileImportManager(
            paths,
            OpenHouseInboxGrouping.MONTH,
            fixedDateProvider());
        OpenHouseImportSource source = source("../nested/report:final?.md");

        OpenHouseImportedFile first = manager.importStream(stream("one"), source);
        OpenHouseImportedFile second = manager.importStream(stream("two"), source);

        Assert.assertEquals("inbox/2026-07/report_final_.md", paths.getWorkspaceRelativePath(first.getFile()));
        Assert.assertEquals("inbox/2026-07/report_final_ (1).md", paths.getWorkspaceRelativePath(second.getFile()));
    }

    private void assertImportsTo(OpenHouseInboxGrouping grouping, String expectedRelativePath) throws Exception {
        OpenHouseWorkspacePaths paths = OpenHouseWorkspacePaths.forTermuxHome(temporaryFolder.newFolder("home-" + grouping.getPreferenceValue()));
        OpenHouseFileImportManager manager = new OpenHouseFileImportManager(paths, grouping, fixedDateProvider());

        OpenHouseImportedFile importedFile = manager.importStream(stream("hello"), source("plain.txt"));

        Assert.assertEquals(expectedRelativePath, paths.getWorkspaceRelativePath(importedFile.getFile()));
    }

    private static OpenHouseImportSource source(String fileName) {
        return OpenHouseImportSource.builder()
            .setSuggestedFileName(fileName)
            .setMimeType("text/plain")
            .build();
    }

    private static ByteArrayInputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static OpenHouseFileImportManager.DateProvider fixedDateProvider() {
        return () -> importedAt();
    }

    private static Date importedAt() {
        return new GregorianCalendar(2026, Calendar.JULY, 9, 10, 30, 0).getTime();
    }
}
