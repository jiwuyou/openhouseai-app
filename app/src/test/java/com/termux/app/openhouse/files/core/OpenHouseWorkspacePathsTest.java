package com.termux.app.openhouse.files.core;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class OpenHouseWorkspacePathsTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void ensureTermuxWorkspaceDirsCreatesRequiredLayout() throws Exception {
        File home = temporaryFolder.newFolder("home");
        OpenHouseWorkspacePaths paths = OpenHouseWorkspacePaths.forTermuxHome(home);

        paths.ensureTermuxWorkspaceDirs();

        Assert.assertTrue(paths.getTermuxWorkspaceDir().isDirectory());
        for (String dir : OpenHouseWorkspacePaths.TOP_LEVEL_DIRS) {
            Assert.assertTrue(new File(paths.getTermuxWorkspaceDir(), dir).isDirectory());
        }
    }

    @Test
    public void mapsTermuxInboxFileToUbuntuAndWorkspacePaths() throws Exception {
        File home = temporaryFolder.newFolder("home");
        OpenHouseWorkspacePaths paths = OpenHouseWorkspacePaths.forTermuxHome(home);
        File file = new File(paths.getInboxDir(), "report.md");

        Assert.assertEquals("inbox/report.md", paths.getWorkspaceRelativePath(file));
        Assert.assertEquals(
            "/root/openhouse/workspace/inbox/report.md",
            paths.getUbuntuPathForTermuxFile(file)
        );
        Assert.assertEquals(
            "openhouse/workspace/inbox/report.md",
            paths.getOpenHouseWorkspacePath(file)
        );
    }

    @Test
    public void mapsLegacyWorkspacePathToSameWorkspaceRelativePath() throws Exception {
        File home = temporaryFolder.newFolder("home");
        OpenHouseWorkspacePaths paths = OpenHouseWorkspacePaths.forTermuxHome(home);
        File file = new File(paths.getLegacyTermuxWorkspaceDir(), "notes/todo.txt");

        Assert.assertEquals("notes/todo.txt", paths.getWorkspaceRelativePath(file));
        Assert.assertEquals(
            "/root/openhouse/workspace/notes/todo.txt",
            paths.getUbuntuPathForTermuxFile(file)
        );
    }

    @Test
    public void mapsUbuntuWorkspacePathBackToTermuxWorkspacePath() throws Exception {
        File home = temporaryFolder.newFolder("home");
        OpenHouseWorkspacePaths paths = OpenHouseWorkspacePaths.forTermuxHome(home);

        Assert.assertEquals(
            OpenHouseWorkspacePaths.normalize(new File(paths.getInboxDir(), "x.txt").getAbsolutePath()),
            paths.getTermuxPathForUbuntuPath("/root/openhouse/workspace/inbox/x.txt")
        );
        Assert.assertNull(paths.getTermuxPathForUbuntuPath("/etc/passwd"));
    }
}
