package com.termux.app.openhouse;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.os.Build;

import com.termux.BuildConfig;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

public final class OpenHouseBundledRuntimeSync {

    private static final String LOG_TAG = "OpenHouseRuntimeSync";
    private static final String BOOTSTRAP_ASSET_DIR = "smallphoneai/bootstrap";
    private static final String BOOTSTRAP_SCRIPTS_ASSET_DIR = BOOTSTRAP_ASSET_DIR + "/scripts";
    private static final String PAYLOAD_ASSET_DIR = "openhouse/product-payloads";
    private static final String SCRIPTS_PUBLIC_ASSET_DIR = "openhouse/scripts-public";
    private static final String MAINTAINER_ASSET_DIR = "maintainer";
    private static final String SYNC_MARKER_NAME = ".apk-sync-marker";

    private OpenHouseBundledRuntimeSync() {
    }

    public static Result sync(Context context) throws IOException {
        if (context == null) {
            throw new IOException("Cannot sync APK runtime assets: context is null");
        }

        Context appContext = context.getApplicationContext();
        AssetManager assetManager = appContext.getAssets();
        File bootstrapDir = getBootstrapDir();
        File payloadDir = getPayloadDir();
        File scriptsPublicDir = getScriptsPublicDir();
        File maintainerDir = getMaintainerDir();

        List<String> bootstrapAssets = collectAssetFiles(assetManager, BOOTSTRAP_ASSET_DIR);
        if (bootstrapAssets.isEmpty()) {
            throw new IOException("APK bundled bootstrap is missing: " + BOOTSTRAP_ASSET_DIR);
        }
        List<String> payloadAssets = collectAssetFiles(assetManager, PAYLOAD_ASSET_DIR);
        if (payloadAssets.isEmpty()) {
            throw new IOException("APK bundled payloads are missing: " + PAYLOAD_ASSET_DIR);
        }
        List<String> scriptsPublicAssets = collectAssetFiles(assetManager, SCRIPTS_PUBLIC_ASSET_DIR);
        List<String> maintainerAssets = collectAssetFiles(assetManager, MAINTAINER_ASSET_DIR);

        List<Entry> entries = new ArrayList<>();
        copyAssetFiles(appContext, bootstrapAssets, BOOTSTRAP_ASSET_DIR, bootstrapDir, entries);
        copyAssetFiles(appContext, payloadAssets, PAYLOAD_ASSET_DIR, payloadDir, entries);
        copyAssetFiles(appContext, scriptsPublicAssets, SCRIPTS_PUBLIC_ASSET_DIR, scriptsPublicDir, entries);
        copyAssetFiles(appContext, maintainerAssets, MAINTAINER_ASSET_DIR, maintainerDir, entries);
        pruneObsoleteFiles(new File(bootstrapDir, "scripts"),
            relativePathSet(bootstrapAssets, BOOTSTRAP_SCRIPTS_ASSET_DIR));
        pruneObsoleteFiles(payloadDir, relativePathSet(payloadAssets, PAYLOAD_ASSET_DIR));
        pruneObsoleteFiles(scriptsPublicDir, relativePathSet(scriptsPublicAssets, SCRIPTS_PUBLIC_ASSET_DIR));
        pruneObsoleteFiles(maintainerDir, relativePathSet(maintainerAssets, MAINTAINER_ASSET_DIR));

        File bootstrapFile = getBootstrapFile();
        if (!bootstrapFile.isFile()) {
            throw new IOException("APK bootstrap sync did not produce " + bootstrapFile.getAbsolutePath());
        }
        setExecutableIfPresent(bootstrapFile);
        File scriptsDir = new File(bootstrapDir, "scripts");
        File[] scripts = scriptsDir.listFiles();
        if (scripts != null) {
            for (File script : scripts) {
                if (script.isFile() && script.getName().endsWith(".sh")) {
                    setExecutableIfPresent(script);
                }
            }
        }
        File[] maintainerScripts = maintainerDir.listFiles();
        if (maintainerScripts != null) {
            for (File script : maintainerScripts) {
                if (script.isFile() && script.getName().endsWith(".sh")) {
                    setExecutableIfPresent(script);
                }
            }
        }

        File payloadManifest = new File(payloadDir, "manifest.json");
        if (!payloadManifest.isFile()) {
            throw new IOException("APK payload sync did not produce " + payloadManifest.getAbsolutePath());
        }

        Collections.sort(entries, Comparator.comparing(entry -> entry.assetPath));
        String treeHash = buildTreeHash(entries);
        File markerFile = new File(bootstrapDir, SYNC_MARKER_NAME);
        String runtimeReport = buildRuntimeReport(appContext, payloadManifest, payloadDir, entries, treeHash);
        writeMarker(appContext, markerFile, entries, treeHash, runtimeReport);

        Result result = new Result(bootstrapFile, payloadDir, markerFile, entries.size(), treeHash, runtimeReport);
        Logger.logInfo(LOG_TAG, "Synced APK runtime assets: " + result.toLogString());
        return result;
    }

    public static File getBootstrapDir() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".smallphoneai-bootstrap");
    }

    public static File getBootstrapFile() {
        return new File(getBootstrapDir(), "bootstrap.sh");
    }

    public static File getPayloadDir() {
        return new File(getBootstrapDir(), "apk-assets/openhouse/product-payloads");
    }

    public static File getScriptsPublicDir() {
        return new File(getBootstrapDir(), "apk-assets/openhouse/scripts-public");
    }

    public static File getMaintainerDir() {
        return new File(getBootstrapDir(), "apk-assets/maintainer");
    }

    private static List<String> collectAssetFiles(AssetManager assetManager, String rootPath) throws IOException {
        List<String> files = new ArrayList<>();
        collectAssetFiles(assetManager, rootPath, rootPath, files);
        Collections.sort(files);
        return files;
    }

    private static void collectAssetFiles(AssetManager assetManager,
                                          String rootPath,
                                          String currentPath,
                                          List<String> files) throws IOException {
        String[] children = assetManager.list(currentPath);
        if (children == null || children.length == 0) {
            if (!currentPath.equals(rootPath)) {
                files.add(currentPath);
            }
            return;
        }

        List<String> sortedChildren = new ArrayList<>();
        Collections.addAll(sortedChildren, children);
        Collections.sort(sortedChildren);
        for (String child : sortedChildren) {
            collectAssetFiles(assetManager, rootPath, currentPath + "/" + child, files);
        }
    }

    private static void copyAssetFiles(Context context,
                                       List<String> assetPaths,
                                       String assetRoot,
                                       File outputRoot,
                                       List<Entry> entries) throws IOException {
        for (String assetPath : assetPaths) {
            String relativePath = relativePath(assetPath, assetRoot);
            File outputFile = new File(outputRoot, relativePath);
            entries.add(copyAssetFile(context, assetPath, outputFile));
        }
    }

    private static Entry copyAssetFile(Context context, String assetPath, File outputFile) throws IOException {
        File parent = outputFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Failed to create runtime asset directory: " + parent.getAbsolutePath());
        }

        File tempFile = new File(parent, "." + outputFile.getName() + ".apk-sync.tmp");
        if (tempFile.isFile() && !tempFile.delete()) {
            throw new IOException("Failed to remove stale runtime asset temp file: " + tempFile.getAbsolutePath());
        }

        MessageDigest digest = newSha256Digest();
        long size = 0L;
        try (InputStream inputStream = new BufferedInputStream(context.getAssets().open(assetPath));
             OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(tempFile, false))) {
            byte[] buffer = new byte[8192];
            int readBytes;
            while ((readBytes = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, readBytes);
                digest.update(buffer, 0, readBytes);
                size += readBytes;
            }
        } catch (IOException e) {
            if (tempFile.isFile() && !tempFile.delete()) {
                Logger.logWarn(LOG_TAG, "Failed to delete incomplete runtime asset temp file: " + tempFile.getAbsolutePath());
            }
            throw new IOException("Failed to copy APK asset " + assetPath + " to " + outputFile.getAbsolutePath()
                + ": " + e.getMessage(), e);
        }

        if (outputFile.exists() && !outputFile.delete()) {
            if (tempFile.isFile() && !tempFile.delete()) {
                Logger.logWarn(LOG_TAG, "Failed to delete runtime asset temp file after target delete failure: "
                    + tempFile.getAbsolutePath());
            }
            throw new IOException("Failed to replace stale runtime asset: " + outputFile.getAbsolutePath());
        }
        if (!tempFile.renameTo(outputFile)) {
            if (tempFile.isFile() && !tempFile.delete()) {
                Logger.logWarn(LOG_TAG, "Failed to delete runtime asset temp file after rename failure: "
                    + tempFile.getAbsolutePath());
            }
            throw new IOException("Failed to install APK asset " + assetPath + " to " + outputFile.getAbsolutePath());
        }

        String expectedSha256 = toHex(digest.digest());
        FileDigest actual = digestFile(outputFile);
        if (actual.size != size || !expectedSha256.equals(actual.sha256)) {
            throw new IOException("Runtime asset verification failed for " + outputFile.getAbsolutePath()
                + ": expected " + expectedSha256 + " (" + size + " bytes), actual "
                + actual.sha256 + " (" + actual.size + " bytes)");
        }

        if (outputFile.getName().endsWith(".sh") || "bootstrap.sh".equals(outputFile.getName())) {
            setExecutableIfPresent(outputFile);
        }
        return new Entry(assetPath, outputFile.getAbsolutePath(), size, expectedSha256);
    }

    private static Set<String> relativePathSet(List<String> assetPaths, String assetRoot) {
        Set<String> relativePaths = new HashSet<>();
        String prefix = assetRoot + "/";
        for (String assetPath : assetPaths) {
            if (assetPath.startsWith(prefix)) {
                relativePaths.add(relativePath(assetPath, assetRoot));
            }
        }
        return relativePaths;
    }

    private static String relativePath(String assetPath, String assetRoot) {
        String prefix = assetRoot + "/";
        return assetPath.startsWith(prefix) ? assetPath.substring(prefix.length()) : assetPath;
    }

    private static void pruneObsoleteFiles(File rootDir, Set<String> expectedRelativeFiles) throws IOException {
        if (rootDir == null || !rootDir.isDirectory()) {
            return;
        }
        pruneObsoleteFiles(rootDir, rootDir, expectedRelativeFiles);
    }

    private static void pruneObsoleteFiles(File rootDir, File current, Set<String> expectedRelativeFiles) throws IOException {
        File[] children = current.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                pruneObsoleteFiles(rootDir, child, expectedRelativeFiles);
                continue;
            }
            String relativePath = rootDir.toURI().relativize(child.toURI()).getPath();
            if (!expectedRelativeFiles.contains(relativePath) && !child.delete()) {
                throw new IOException("Failed to remove obsolete APK-managed runtime asset: " + child.getAbsolutePath());
            }
        }
    }

    private static void setExecutableIfPresent(File file) {
        if (file != null && file.isFile() && !file.setExecutable(true, true)) {
            Logger.logWarn(LOG_TAG, "Failed to mark runtime asset executable: " + file.getAbsolutePath());
        }
    }

    private static FileDigest digestFile(File file) throws IOException {
        MessageDigest digest = newSha256Digest();
        long size = 0L;
        try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[8192];
            int readBytes;
            while ((readBytes = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, readBytes);
                size += readBytes;
            }
        }
        return new FileDigest(size, toHex(digest.digest()));
    }

    private static MessageDigest newSha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 digest is unavailable", e);
        }
    }

    private static String buildTreeHash(List<Entry> entries) throws IOException {
        MessageDigest digest = newSha256Digest();
        for (Entry entry : entries) {
            digest.update(entry.assetPath.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\t');
            digest.update(Long.toString(entry.size).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\t');
            digest.update(entry.sha256.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return toHex(digest.digest());
    }

    private static void writeMarker(Context context,
                                    File markerFile,
                                    List<Entry> entries,
                                    String treeHash,
                                    String runtimeReport) throws IOException {
        File parent = markerFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Failed to create runtime sync marker directory: " + parent.getAbsolutePath());
        }

        StringBuilder builder = new StringBuilder();
        builder.append("schema=1\n");
        builder.append("package_name=").append(context.getPackageName()).append('\n');
        builder.append("package_version_code=").append(getPackageVersionCode(context)).append('\n');
        builder.append("package_version_name=").append(getPackageVersionName(context)).append('\n');
        builder.append("synced_at_ms=").append(System.currentTimeMillis()).append('\n');
        builder.append("bootstrap_asset_dir=").append(BOOTSTRAP_ASSET_DIR).append('\n');
        builder.append("payload_asset_dir=").append(PAYLOAD_ASSET_DIR).append('\n');
        builder.append("scripts_public_asset_dir=").append(SCRIPTS_PUBLIC_ASSET_DIR).append('\n');
        builder.append("maintainer_asset_dir=").append(MAINTAINER_ASSET_DIR).append('\n');
        builder.append("asset_count=").append(entries.size()).append('\n');
        builder.append("asset_tree_sha256=").append(treeHash).append('\n');
        builder.append("runtime_report_begin\n");
        builder.append(runtimeReport == null ? "" : runtimeReport);
        if (runtimeReport != null && !runtimeReport.endsWith("\n")) {
            builder.append('\n');
        }
        builder.append("runtime_report_end\n");
        for (Entry entry : entries) {
            builder.append("asset=")
                .append(entry.assetPath)
                .append('\t')
                .append(entry.size)
                .append('\t')
                .append(entry.sha256)
                .append('\t')
                .append(entry.outputPath)
                .append('\n');
        }

        try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(markerFile, false))) {
            outputStream.write(builder.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String buildRuntimeReport(Context context,
                                             File manifestFile,
                                             File payloadDir,
                                             List<Entry> entries,
                                             String treeHash) {
        List<String> lines = new ArrayList<>();
        lines.add("OpenHouse runtime manifest:");
        lines.add("APK: package=" + context.getPackageName()
            + " versionName=" + valueOrUnknown(getPackageVersionName(context))
            + " versionCode=" + getPackageVersionCode(context)
            + " packageVariant=" + valueOrUnknown(BuildConfig.TERMUX_PACKAGE_VARIANT));
        lines.add("bootstrap: assetCount=" + entries.size()
            + " treeSha256=" + shortSha(treeHash));
        lines.add("payloadManifest: path=" + manifestFile.getAbsolutePath());

        JSONObject manifest = readJsonObject(manifestFile, lines);
        if (manifest == null) {
            lines.add("compatibility: failed; payload manifest is unreadable");
            return joinLines(lines);
        }

        lines.add("payloadManifest: schema=" + manifest.optInt("schema", 0)
            + " generatedAt=" + valueOrUnknown(manifest.optString("generatedAt", "")));

        JSONObject serviceManager = findManifestComponent(manifest, "service-manager");
        JSONObject piAgent = findManifestComponent(manifest, "pi-agent");
        JSONObject piWeb = findManifestComponent(manifest, "pi-web");
        JSONObject aionui = findManifestComponent(manifest, "aionui-web");
        String registryApiVersion = registryApiVersion(manifest, serviceManager);

        lines.add(componentReport("service-manager", serviceManager, payloadDir, entries));
        lines.add("registryApi: version=" + valueOrUnknown(registryApiVersion));
        lines.add(componentReport("pi-agent", piAgent, payloadDir, entries));
        lines.add(componentReport("pi-web", piWeb, payloadDir, entries));
        lines.add(componentReport("aionui-web", aionui, payloadDir, entries));
        lines.add("compatibility: " + compatibilityReport(registryApiVersion, serviceManager, piAgent, piWeb, aionui, payloadDir, entries));
        return joinLines(lines);
    }

    private static JSONObject readJsonObject(File file, List<String> lines) {
        try {
            return new JSONObject(readUtf8File(file));
        } catch (Exception e) {
            lines.add("payloadManifest: error=" + valueOrUnknown(e.getMessage()));
            return null;
        }
    }

    private static String readUtf8File(File file) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[8192];
            int readBytes;
            while ((readBytes = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, readBytes);
            }
        }
        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }

    private static JSONObject findManifestComponent(JSONObject manifest, String id) {
        JSONArray components = manifest.optJSONArray("components");
        if (components == null) {
            components = manifest.optJSONArray("payloads");
        }
        if (components == null) {
            return null;
        }
        for (int i = 0; i < components.length(); i++) {
            JSONObject component = components.optJSONObject(i);
            if (component != null && id.equals(component.optString("id", ""))) {
                return component;
            }
        }
        return null;
    }

    private static String componentReport(String id, JSONObject component, File payloadDir, List<Entry> entries) {
        if (component == null) {
            return id + ": missing";
        }
        String archive = component.optString("archive", "");
        String expectedSha256 = component.optString("sha256", "");
        long expectedSize = component.optLong("size", -1L);
        Entry entry = findEntryForArchive(entries, archive);
        String status;
        if (archive.isEmpty()) {
            status = "missing-archive-field";
        } else if (entry == null) {
            File fallback = new File(payloadDir, archive);
            status = fallback.isFile() ? "present-unverified" : "missing-archive-file";
        } else if (entry.size == expectedSize && entry.sha256.equalsIgnoreCase(expectedSha256)) {
            status = "ok";
        } else {
            status = "manifest-mismatch";
        }
        return id
            + ": version=" + componentVersion(component)
            + " archive=" + valueOrUnknown(archive)
            + " size=" + expectedSize
            + " sha256=" + shortSha(expectedSha256)
            + " status=" + status;
    }

    private static Entry findEntryForArchive(List<Entry> entries, String archive) {
        if (archive == null || archive.isEmpty()) {
            return null;
        }
        String suffix = "/" + archive;
        for (Entry entry : entries) {
            if (entry.assetPath.equals(PAYLOAD_ASSET_DIR + suffix) || entry.assetPath.endsWith(suffix)) {
                return entry;
            }
        }
        return null;
    }

    private static String componentVersion(JSONObject component) {
        if (component == null) {
            return "unknown";
        }
        String version = component.optString("version", "");
        if (!version.isEmpty()) {
            return version;
        }
        String sourceCommit = component.optString("sourceCommit", "");
        if (!sourceCommit.isEmpty()) {
            return "commit:" + shortSha(sourceCommit);
        }
        String binarySha256 = component.optString("binarySha256", "");
        if (!binarySha256.isEmpty()) {
            return "binary-sha256:" + shortSha(binarySha256);
        }
        String sha256 = component.optString("sha256", "");
        if (!sha256.isEmpty()) {
            return "archive-sha256:" + shortSha(sha256);
        }
        return "unknown";
    }

    private static String registryApiVersion(JSONObject manifest, JSONObject serviceManager) {
        String value = firstNonEmpty(
            manifest.optString("registryApiVersion", ""),
            manifest.optString("registryApi", ""),
            nestedString(manifest, "compatibility", "registryApiVersion"),
            nestedString(manifest, "compatibility", "registryApi"),
            serviceManager == null ? "" : serviceManager.optString("registryApiVersion", ""),
            serviceManager == null ? "" : serviceManager.optString("registryApi", "")
        );
        return value;
    }

    private static String compatibilityReport(String registryApiVersion,
                                              JSONObject serviceManager,
                                              JSONObject piAgent,
                                              JSONObject piWeb,
                                              JSONObject aionui,
                                              File payloadDir,
                                              List<Entry> entries) {
        List<String> problems = new ArrayList<>();
        addComponentProblem(problems, "service-manager", serviceManager, payloadDir, entries);
        addComponentProblem(problems, "pi-agent", piAgent, payloadDir, entries);
        addComponentProblem(problems, "pi-web", piWeb, payloadDir, entries);
        addComponentProblem(problems, "aionui-web", aionui, payloadDir, entries);
        if (registryApiVersion == null || registryApiVersion.isEmpty()) {
            problems.add("registryApiVersion=unknown");
        }
        if (problems.isEmpty()) {
            return "ok";
        }
        return "warning; " + joinComma(problems);
    }

    private static void addComponentProblem(List<String> problems,
                                            String id,
                                            JSONObject component,
                                            File payloadDir,
                                            List<Entry> entries) {
        if (component == null) {
            problems.add(id + "=missing");
            return;
        }
        String archive = component.optString("archive", "");
        if (archive.isEmpty()) {
            problems.add(id + ".archive=missing");
            return;
        }
        Entry entry = findEntryForArchive(entries, archive);
        if (entry == null && !new File(payloadDir, archive).isFile()) {
            problems.add(id + ".archiveFile=missing");
            return;
        }
        long expectedSize = component.optLong("size", -1L);
        String expectedSha256 = component.optString("sha256", "");
        if (entry != null && (entry.size != expectedSize || !entry.sha256.equalsIgnoreCase(expectedSha256))) {
            problems.add(id + ".manifestDigest=mismatch");
        }
    }

    private static String nestedString(JSONObject object, String nestedKey, String valueKey) {
        JSONObject nested = object.optJSONObject(nestedKey);
        return nested == null ? "" : nested.optString(valueKey, "");
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static String valueOrUnknown(String value) {
        return value == null || value.isEmpty() ? "unknown" : value;
    }

    private static String shortSha(String value) {
        if (value == null || value.isEmpty()) {
            return "unknown";
        }
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private static String joinComma(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private static String joinLines(List<String> lines) {
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            builder.append(line == null ? "" : line).append('\n');
        }
        return builder.toString();
    }

    private static long getPackageVersionCode(Context context) {
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return packageInfo.getLongVersionCode();
            }
            return packageInfo.versionCode;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read package versionCode for runtime sync marker", e);
            return -1L;
        }
    }

    private static String getPackageVersionName(Context context) {
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionName == null ? "" : packageInfo.versionName;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read package versionName for runtime sync marker", e);
            return "";
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }

    private static final class Entry {
        final String assetPath;
        final String outputPath;
        final long size;
        final String sha256;

        Entry(String assetPath, String outputPath, long size, String sha256) {
            this.assetPath = assetPath;
            this.outputPath = outputPath;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    private static final class FileDigest {
        final long size;
        final String sha256;

        FileDigest(long size, String sha256) {
            this.size = size;
            this.sha256 = sha256;
        }
    }

    public static final class Result {
        public final File bootstrapFile;
        public final File payloadDir;
        public final File markerFile;
        public final int assetCount;
        public final String treeHash;
        public final String runtimeReport;

        Result(File bootstrapFile, File payloadDir, File markerFile, int assetCount, String treeHash, String runtimeReport) {
            this.bootstrapFile = bootstrapFile;
            this.payloadDir = payloadDir;
            this.markerFile = markerFile;
            this.assetCount = assetCount;
            this.treeHash = treeHash;
            this.runtimeReport = runtimeReport == null ? "" : runtimeReport;
        }

        public String toLogString() {
            return assetCount + " assets, tree_sha256=" + treeHash
                + ", bootstrap=" + bootstrapFile.getAbsolutePath()
                + ", payloads=" + payloadDir.getAbsolutePath()
                + ", marker=" + markerFile.getAbsolutePath();
        }
    }
}
