package com.termux.app.openhouse.files.core;

import java.io.File;
import java.io.IOException;

public final class OpenHouseUbuntuPaths {

    private static final String PROOT_CONTAINERS_ROOT = "usr/var/lib/proot-distro/containers/ubuntu/rootfs/root";
    private static final String PROOT_INSTALLED_ROOT = "usr/var/lib/proot-distro/installed-rootfs/ubuntu/root";

    private OpenHouseUbuntuPaths() {
    }

    public static File findUbuntuHomeDir(File termuxHomeDir) {
        if (termuxHomeDir == null) {
            return null;
        }
        OpenHouseWorkspacePaths paths = OpenHouseWorkspacePaths.forTermuxHome(termuxHomeDir);
        File workspaceUbuntuHome = new File(paths.getSubdir(OpenHouseWorkspacePaths.DIR_UBUNTU), "root");
        File legacyUbuntuHome = new File(termuxHomeDir, "ubuntu-root");
        File filesDir = termuxHomeDir.getParentFile();

        File resolved = firstUsableUbuntuHome(
            workspaceUbuntuHome,
            legacyUbuntuHome,
            filesDir == null ? null : new File(filesDir, PROOT_CONTAINERS_ROOT),
            filesDir == null ? null : new File(filesDir, PROOT_INSTALLED_ROOT)
        );
        return resolved == null ? null : resolved.getAbsoluteFile();
    }

    public static File findUbuntuWorkspaceDir(File termuxHomeDir) {
        File ubuntuHome = findUbuntuHomeDir(termuxHomeDir);
        if (ubuntuHome == null) {
            return null;
        }
        File workspace = new File(ubuntuHome, OpenHouseWorkspacePaths.WORKSPACE_RELATIVE_PATH);
        return workspace.isDirectory() ? workspace.getAbsoluteFile() : null;
    }

    public static boolean isUsableUbuntuHome(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        try {
            String canonical = OpenHouseWorkspacePaths.normalize(dir.getCanonicalPath());
            return canonical != null
                && canonical.contains("/var/lib/proot-distro/")
                && (canonical.endsWith("/rootfs/root")
                    || canonical.endsWith("/installed-rootfs/ubuntu/root"));
        } catch (IOException e) {
            return false;
        }
    }

    private static File firstUsableUbuntuHome(File... candidates) {
        if (candidates == null) {
            return null;
        }
        for (File candidate : candidates) {
            if (isUsableUbuntuHome(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
