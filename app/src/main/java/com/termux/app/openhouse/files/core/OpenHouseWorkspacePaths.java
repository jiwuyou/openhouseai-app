package com.termux.app.openhouse.files.core;

import java.io.File;
import java.io.IOException;

public final class OpenHouseWorkspacePaths {

    public static final String OPENHOUSE_DIR_NAME = "openhouse";
    public static final String WORKSPACE_DIR_NAME = "workspace";
    public static final String WORKSPACE_RELATIVE_PATH = OPENHOUSE_DIR_NAME + "/" + WORKSPACE_DIR_NAME;
    public static final String LEGACY_WORKSPACE_RELATIVE_PATH = WORKSPACE_DIR_NAME;
    public static final String UBUNTU_WORKSPACE_PATH = "/root/openhouse/workspace";

    public static final String DIR_ANDROID = "android";
    public static final String DIR_TERMUX = "termux";
    public static final String DIR_UBUNTU = "ubuntu";
    public static final String DIR_INBOX = "inbox";
    public static final String DIR_EXPORT = "export";
    public static final String DIR_NETWORK = "network";
    public static final String DIR_CONTAINERS = "containers";

    public static final String[] TOP_LEVEL_DIRS = new String[]{
        DIR_ANDROID,
        DIR_TERMUX,
        DIR_UBUNTU,
        DIR_INBOX,
        DIR_EXPORT,
        DIR_NETWORK,
        DIR_CONTAINERS
    };

    private final File termuxHomeDir;
    private final File termuxWorkspaceDir;
    private final File legacyTermuxWorkspaceDir;
    private final File ubuntuWorkspaceDir;

    private OpenHouseWorkspacePaths(File termuxHomeDir, File ubuntuWorkspaceDir) {
        this.termuxHomeDir = termuxHomeDir;
        this.termuxWorkspaceDir = new File(termuxHomeDir, WORKSPACE_RELATIVE_PATH);
        this.legacyTermuxWorkspaceDir = new File(termuxHomeDir, LEGACY_WORKSPACE_RELATIVE_PATH);
        this.ubuntuWorkspaceDir = ubuntuWorkspaceDir;
    }

    public static OpenHouseWorkspacePaths forTermuxHome(File termuxHomeDir) {
        return new OpenHouseWorkspacePaths(termuxHomeDir, new File(UBUNTU_WORKSPACE_PATH));
    }

    public static OpenHouseWorkspacePaths forTermuxHome(String termuxHomePath) {
        return forTermuxHome(new File(termuxHomePath));
    }

    public File getTermuxHomeDir() {
        return termuxHomeDir;
    }

    public File getTermuxWorkspaceDir() {
        return termuxWorkspaceDir;
    }

    public File getLegacyTermuxWorkspaceDir() {
        return legacyTermuxWorkspaceDir;
    }

    public File getUbuntuWorkspaceDir() {
        return ubuntuWorkspaceDir;
    }

    public File getSubdir(String name) {
        return new File(termuxWorkspaceDir, name);
    }

    public File getInboxDir() {
        return getSubdir(DIR_INBOX);
    }

    public File getExportDir() {
        return getSubdir(DIR_EXPORT);
    }

    public File getNetworkDir() {
        return getSubdir(DIR_NETWORK);
    }

    public File getContainersDir() {
        return getSubdir(DIR_CONTAINERS);
    }

    public void ensureTermuxWorkspaceDirs() throws IOException {
        ensureDirectory(termuxWorkspaceDir);
        for (String dir : TOP_LEVEL_DIRS) {
            ensureDirectory(getSubdir(dir));
        }
    }

    public String getWorkspaceRelativePath(File termuxFile) {
        String normalizedPath = normalize(termuxFile.getAbsolutePath());
        String workspacePath = normalize(termuxWorkspaceDir.getAbsolutePath());
        String legacyWorkspacePath = normalize(legacyTermuxWorkspaceDir.getAbsolutePath());

        if (isSameOrChild(normalizedPath, workspacePath)) {
            return relativePath(workspacePath, normalizedPath);
        }
        if (isSameOrChild(normalizedPath, legacyWorkspacePath)) {
            return relativePath(legacyWorkspacePath, normalizedPath);
        }
        return null;
    }

    public String getOpenHouseWorkspacePath(File termuxFile) {
        String relativePath = getWorkspaceRelativePath(termuxFile);
        if (relativePath == null || relativePath.isEmpty()) {
            return WORKSPACE_RELATIVE_PATH;
        }
        return WORKSPACE_RELATIVE_PATH + "/" + relativePath;
    }

    public String getUbuntuPathForTermuxFile(File termuxFile) {
        String relativePath = getWorkspaceRelativePath(termuxFile);
        if (relativePath == null || relativePath.isEmpty()) {
            return null;
        }
        return normalize(new File(ubuntuWorkspaceDir, relativePath).getPath());
    }

    public String getTermuxPathForUbuntuPath(String ubuntuPath) {
        String normalizedUbuntuPath = normalize(ubuntuPath);
        String ubuntuWorkspacePath = normalize(ubuntuWorkspaceDir.getPath());
        if (!isSameOrChild(normalizedUbuntuPath, ubuntuWorkspacePath)) {
            return null;
        }
        String relativePath = relativePath(ubuntuWorkspacePath, normalizedUbuntuPath);
        if (relativePath.isEmpty()) {
            return normalize(termuxWorkspaceDir.getAbsolutePath());
        }
        return normalize(new File(termuxWorkspaceDir, relativePath).getAbsolutePath());
    }

    public static boolean isContentUri(String uri) {
        return uri != null && uri.regionMatches(true, 0, "content://", 0, "content://".length());
    }

    public static String normalize(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        String normalized = path.replace('\\', '/');
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static void ensureDirectory(File dir) throws IOException {
        if (dir.isDirectory()) {
            return;
        }
        if (dir.exists()) {
            throw new IOException("Path exists but is not a directory: " + dir.getAbsolutePath());
        }
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Cannot create directory: " + dir.getAbsolutePath());
        }
    }

    private static boolean isSameOrChild(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    private static String relativePath(String root, String path) {
        if (path.equals(root)) {
            return "";
        }
        return path.substring(root.length() + 1);
    }
}
