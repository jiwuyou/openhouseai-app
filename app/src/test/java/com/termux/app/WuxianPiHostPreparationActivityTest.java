package com.termux.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class WuxianPiHostPreparationActivityTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void installsStableSetupCommandAtomically() throws Exception {
        File source = temporaryFolder.newFile("wuxianpi-setup-source");
        Files.write(source.toPath(), "#!/bin/sh\nprintf ready\n".getBytes(StandardCharsets.UTF_8));
        File target = new File(temporaryFolder.newFolder("bin"), "wuxianpi-setup");

        File installed = WuxianPiHostPreparationActivity.installSetupCommand(source, target);

        assertEquals(target, installed);
        assertTrue(installed.isFile());
        assertTrue(installed.canExecute());
        assertEquals("#!/bin/sh\nprintf ready\n", new String(
            Files.readAllBytes(installed.toPath()), StandardCharsets.UTF_8));
    }
}
