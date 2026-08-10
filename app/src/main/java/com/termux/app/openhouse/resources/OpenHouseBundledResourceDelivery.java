package com.termux.app.openhouse.resources;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

/** Stages one complete APK resource set for first-install bootstrap or later AI-directed updates. */
public final class OpenHouseBundledResourceDelivery {

    public static final String ROOT_RELATIVE_PATH = ".local/share/openhouseai/update-resources";
    public static final String PENDING_MARKER_NAME = "PENDING_APK_RESOURCES.json";
    public static final String AI_REQUEST_SENTENCE =
        "请查看 $HOME/.local/share/openhouseai/update-resources 中日期最新的资源目录，阅读其中的 manifest 和 "
            + "AI_UPDATE_GUIDE.md，检查当前环境后完成合适的更新。";

    private static final String COMPLETE_NAME = ".complete";
    private static final String DIRECTORY_PENDING_NAME = ".pending";
    private static final String GUIDE_NAME = "AI_UPDATE_GUIDE.md";
    private static final String GUIDE_ASSET_PATH = "openhouse/product-payloads/" + GUIDE_NAME;
    private static final String[][] ASSET_ROOTS = {
        {"smallphoneai/bootstrap", "bootstrap"},
        {"maintainer", "maintainer"},
        {"openhouse/scripts-public", "scripts-public"},
        {"openhouse/product-payloads", "product-payloads"}
    };
    private static final String[] REQUIRED_DIRECTORIES = {
        "bootstrap", "maintainer", "scripts-public", "product-payloads"
    };
    private static final String[] REQUIRED_REGULAR_FILES = {
        COMPLETE_NAME,
        GUIDE_NAME,
        "bootstrap/bootstrap.sh",
        "bootstrap/scripts/00-check-termux.sh",
        "bootstrap/scripts/wuxianpi-setup",
        "bootstrap/scripts/50-install-runtime-components.sh",
        "bootstrap/scripts/60-start-smallphone.sh",
        "maintainer/install-runtime-components.sh",
        "maintainer/_termux-services-env.sh",
        "maintainer/start-control-plane-termux-native.sh",
        "maintainer/repair-control-plane-termux-native.sh",
        "maintainer/inspect-control-plane-termux-native.sh",
        "maintainer/control-plane-manifest.json",
        "product-payloads/manifest.json",
        "product-payloads/payload-manifest.json",
        "product-payloads/resource-set.json",
        "product-payloads/service-manager.tgz",
        "product-payloads/openhouse-control-plane.tgz",
        "product-payloads/runtime-aarch64.tgz",
        "product-payloads/wuyou.tgz",
        "product-payloads/openhouse-web.tgz"
    };
    private static final String[] INTEGRITY_ROOTS = {
        "bootstrap", "maintainer", "scripts-public"
    };
    private static final String VERIFIED_FILES_KEY = "verifiedFiles";
    private static final int BUFFER_SIZE = 128 * 1024;
    private static final Object LOCK = new Object();

    private OpenHouseBundledResourceDelivery() {
    }

    public enum Reason {
        FIRST_INSTALL("first_install"),
        APK_UPDATE("apk_update");

        final String value;

        Reason(String value) {
            this.value = value;
        }
    }

    public static Result deliver(Context context, PackageInfo packageInfo, Reason reason) {
        if (context == null || packageInfo == null || reason == null) {
            return Result.failure(null, "Missing resource delivery input");
        }
        Context app = context.getApplicationContext();
        long versionCode = packageVersionCode(packageInfo);
        if (versionCode <= 0L) {
            return Result.failure(null, "Invalid APK versionCode");
        }
        return deliverForTesting(
            new File(TermuxConstants.TERMUX_HOME_DIR_PATH),
            packageInfo.versionName,
            versionCode,
            packageInfo.lastUpdateTime,
            reason,
            new AndroidAssetSource(app)
        );
    }

    public static boolean isStagedPackage(PackageInfo packageInfo) {
        if (packageInfo == null) {
            return false;
        }
        return verifyExisting(packageInfo).isSuccess();
    }

    /** Verifies the current APK's staged resources without publishing or changing a pending marker. */
    public static Result verifyExisting(PackageInfo packageInfo) {
        if (packageInfo == null) {
            return Result.failure(null, "Missing package info for staged resource verification");
        }
        return verifyExistingForTesting(
            new File(TermuxConstants.TERMUX_HOME_DIR_PATH),
            packageInfo.versionName,
            packageVersionCode(packageInfo),
            packageInfo.lastUpdateTime
        );
    }

    public static boolean clearPendingAiUpdateMarker() {
        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        return clearPendingAiUpdateMarker(new File(home, ROOT_RELATIVE_PATH));
    }

    public static boolean clearPendingAiUpdateMarker(long versionCode, Reason reason) {
        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        return clearPendingAiUpdateMarker(new File(home, ROOT_RELATIVE_PATH), versionCode, reason);
    }

    static Result deliverForTesting(File home,
                                    String versionName,
                                    long versionCode,
                                    long packageLastUpdateTime,
                                    Reason reason,
                                    AssetSource source) {
        synchronized (LOCK) {
            File root = new File(home, ROOT_RELATIVE_PATH);
            File target = expectedResourceDirectory(
                home, versionName, versionCode, packageLastUpdateTime);
            File temporary = null;
            try {
                if (versionCode <= 0L || packageLastUpdateTime < 0L || reason == null || source == null) {
                    throw new IOException("Invalid resource delivery input");
                }
                requireReadyHome(home);
                ensurePrivatePath(home, ROOT_RELATIVE_PATH);
                requireDirectChild(root, target);

                boolean reused = isCompleteTarget(target, versionCode);
                if (!reused) {
                    temporary = createTemporaryDirectory(root, target.getName());
                    copyCompleteResourceSet(source, temporary);
                    writeCompleteMarker(temporary, versionName, versionCode,
                        utcDate(new Date(packageLastUpdateTime)));
                    if (!isCompleteTarget(temporary, versionCode)) {
                        throw new IOException("Staged APK resources failed completeness verification");
                    }
                    syncDirectoriesBestEffort(temporary);
                    if (target.exists()) {
                        if (!target.isDirectory() || Files.isSymbolicLink(target.toPath())) {
                            throw new IOException("Unsafe incomplete destination: " + target.getName());
                        }
                        deleteRecursivelyStrict(target);
                    }
                    moveDirectoryAtomically(temporary, target);
                    temporary = null;
                    setPrivateDirectory(target);
                    syncDirectoryBestEffort(root);
                }

                if (!isCompleteTarget(target, versionCode)) {
                    throw new IOException("Published APK resources failed completeness verification");
                }
                deleteOldAndroidVersionDirectories(root, target);
                writePendingMarker(root, versionName, versionCode, target.getName(), reason);
                return Result.success(target, reused);
            } catch (Exception e) {
                return Result.failure(target, safeMessage(e));
            } finally {
                if (temporary != null) {
                    deleteOwnedTemporary(temporary, root);
                }
            }
        }
    }

    static Result verifyExistingForTesting(File home,
                                           String versionName,
                                           long versionCode,
                                           long packageLastUpdateTime) {
        synchronized (LOCK) {
            File target = expectedResourceDirectory(
                home, versionName, versionCode, packageLastUpdateTime);
            if (versionCode <= 0L || packageLastUpdateTime < 0L) {
                return Result.failure(target, "Invalid staged resource verification input");
            }
            if (!isCompleteTarget(target, versionCode)) {
                return Result.failure(target, "Staged APK resources are missing or failed integrity verification");
            }
            return Result.success(target, true);
        }
    }

    static boolean clearPendingAiUpdateMarker(File root) {
        synchronized (LOCK) {
            try {
                File marker = pendingMarker(root);
                if (!marker.exists()) {
                    return true;
                }
                if (!isRegularFile(marker)) {
                    return false;
                }
                JSONObject value = readJson(marker);
                clearDirectoryPendingMarker(root, value);
                if (!marker.delete()) return false;
                syncDirectoryBestEffort(root);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    static boolean clearPendingAiUpdateMarker(File root, long versionCode, Reason reason) {
        synchronized (LOCK) {
            try {
                File marker = pendingMarker(root);
                if (!marker.exists()) {
                    return true;
                }
                if (!isRegularFile(marker) || reason == null) {
                    return false;
                }
                JSONObject value = readJson(marker);
                if (value.optLong("apkVersionCode", -1L) != versionCode
                    || !reason.value.equals(value.optString("reason", ""))) {
                    return false;
                }
                clearDirectoryPendingMarker(root, value);
                if (!marker.delete()) {
                    return false;
                }
                syncDirectoryBestEffort(root);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    static boolean isCompleteTarget(File target, long expectedVersionCode) {
        try {
            if (target == null || !target.isDirectory() || Files.isSymbolicLink(target.toPath())) {
                return false;
            }
            for (String path : REQUIRED_DIRECTORIES) {
                File directory = new File(target, path);
                if (!directory.isDirectory() || Files.isSymbolicLink(directory.toPath())) {
                    return false;
                }
            }
            for (String path : REQUIRED_REGULAR_FILES) {
                if (!isRegularFile(new File(target, path))) {
                    return false;
                }
            }
            JSONObject complete = readJson(new File(target, COMPLETE_NAME));
            return complete.optLong("apkVersionCode", -1L) == expectedVersionCode
                && verifyIntegrityEntries(target, complete.optJSONArray(VERIFIED_FILES_KEY))
                && verifyBundledResourceSet(target, expectedVersionCode);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static File createTemporaryDirectory(File root, String directoryName) throws Exception {
        File temporary = new File(root,
            "." + directoryName + ".android-tmp-" + UUID.randomUUID());
        requireDirectChild(root, temporary);
        if (!temporary.mkdir()) {
            throw new IOException("Unable to create private resource temporary directory");
        }
        setPrivateDirectory(temporary);
        return temporary;
    }

    private static void copyCompleteResourceSet(AssetSource source, File destination) throws Exception {
        for (String[] mapping : ASSET_ROOTS) {
            String[] children = source.list(mapping[0]);
            if (children == null || children.length == 0) {
                throw new IOException("Bundled resource root is missing: " + mapping[0]);
            }
            File outputRoot = new File(destination, mapping[1]);
            requireDirectChild(destination, outputRoot);
            if (!outputRoot.mkdir()) {
                throw new IOException("Unable to create private resource root: " + mapping[1]);
            }
            setPrivateDirectory(outputRoot);
            copyAssetDirectory(source, mapping[0], outputRoot);
        }
        try (InputStream input = new BufferedInputStream(source.open(GUIDE_ASSET_PATH))) {
            copyAssetFile(input, new File(destination, GUIDE_NAME));
        }
    }

    private static void copyAssetDirectory(AssetSource source, String assetPath, File destination)
        throws Exception {
        String[] children = source.list(assetPath);
        if (children == null) {
            throw new IOException("Unable to list bundled resources: " + assetPath);
        }
        Arrays.sort(children);
        for (String child : children) {
            validateAssetName(child);
            String childAssetPath = assetPath + "/" + child;
            File childDestination = new File(destination, child);
            requireDirectChild(destination, childDestination);
            String[] grandchildren = source.list(childAssetPath);
            if (grandchildren != null && grandchildren.length > 0) {
                if (!childDestination.mkdir()) {
                    throw new IOException("Unable to create private resource directory");
                }
                setPrivateDirectory(childDestination);
                copyAssetDirectory(source, childAssetPath, childDestination);
                continue;
            }
            try (InputStream input = new BufferedInputStream(source.open(childAssetPath))) {
                copyAssetFile(input, childDestination);
            } catch (FileNotFoundException emptyDirectory) {
                if (!childDestination.mkdir()) {
                    throw emptyDirectory;
                }
                setPrivateDirectory(childDestination);
            }
        }
    }

    private static void copyAssetFile(InputStream input, File destination) throws Exception {
        if (destination.exists()) {
            throw new IOException("Duplicate bundled resource path");
        }
        try (FileOutputStream fileOutput = new FileOutputStream(destination, false);
             BufferedOutputStream output = new BufferedOutputStream(fileOutput, BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
            fileOutput.getFD().sync();
        }
        setPrivateFile(destination);
    }

    private static void writeCompleteMarker(File directory,
                                            String versionName,
                                            long versionCode,
                                            String date) throws Exception {
        JSONArray verifiedFiles = new JSONArray();
        for (IntegrityEntry entry : collectIntegrityEntries(directory)) {
            verifiedFiles.put(new JSONObject()
                .put("path", entry.path)
                .put("size", entry.size)
                .put("sha256", entry.sha256));
        }
        JSONObject value = new JSONObject()
            .put("schemaVersion", 3)
            .put("apkVersionName", versionName == null ? "" : versionName)
            .put("apkVersionCode", versionCode)
            .put("date", date)
            .put("layout", "unified-apk-resources")
            .put(VERIFIED_FILES_KEY, verifiedFiles);
        writeSyncedFile(new File(directory, COMPLETE_NAME),
            (value.toString() + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writePendingMarker(File root,
                                           String versionName,
                                           long versionCode,
                                           String resourceDirectoryName,
                                           Reason reason) throws Exception {
        JSONObject value = new JSONObject()
            .put("apkVersionName", versionName == null ? "" : versionName)
            .put("apkVersionCode", versionCode)
            .put("resourceDir", new File(root, resourceDirectoryName).getAbsolutePath())
            .put("reason", reason.value)
            .put("status", "pending_ai");
        byte[] bytes = (value.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        File marker = pendingMarker(root);
        File temporary = new File(root,
            ".PENDING_APK_RESOURCES.android-tmp-" + UUID.randomUUID());
        requireDirectChild(root, temporary);
        try {
            writeSyncedFile(temporary, bytes);
            moveFileAtomically(temporary, marker);
            setPrivateFile(marker);
            File resourceDirectory = new File(root, resourceDirectoryName);
            requireDirectChild(root, resourceDirectory);
            writeSyncedFile(new File(resourceDirectory, DIRECTORY_PENDING_NAME), bytes);
            syncDirectoryBestEffort(root);
        } finally {
            if (temporary.exists()) {
                temporary.delete();
            }
        }
    }

    private static File pendingMarker(File root) throws Exception {
        File marker = new File(root, PENDING_MARKER_NAME);
        requireDirectChild(root, marker);
        return marker;
    }

    private static void clearDirectoryPendingMarker(File root, JSONObject marker) {
        try {
            File resourceDirectory = new File(marker.optString("resourceDir", ""));
            requireDirectChild(root, resourceDirectory);
            File pending = new File(resourceDirectory, DIRECTORY_PENDING_NAME);
            if (isRegularFile(pending)) pending.delete();
        } catch (Exception ignored) {
            // The global marker remains authoritative when an old or malformed directory path is present.
        }
    }

    private static JSONObject readJson(File file) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    private static boolean verifyBundledResourceSet(File target, long expectedVersionCode) {
        try {
            JSONObject resourceSet = readJson(new File(target, "product-payloads/resource-set.json"));
            if (resourceSet.optInt("schema", 0) != 2
                || !"openhouse-core-stack".equals(resourceSet.optString("id", ""))
                || !"arm64-v8a".equals(resourceSet.optString("abi", ""))
                || resourceSet.optLong("minApkVersionCode", Long.MAX_VALUE) > expectedVersionCode) {
                return false;
            }
            Map<String, String> archives = new LinkedHashMap<>();
            archives.put("service-manager", "service-manager.tgz");
            archives.put("openhouse-control-plane", "openhouse-control-plane.tgz");
            archives.put("openhouse-runtime", "runtime-aarch64.tgz");
            archives.put("wuyou", "wuyou.tgz");
            archives.put("openhouse-web", "openhouse-web.tgz");
            JSONArray resources = resourceSet.optJSONArray("resources");
            if (resources == null || resources.length() != archives.size()) {
                return false;
            }
            Map<String, String> actual = new LinkedHashMap<>();
            for (int index = 0; index < resources.length(); index++) {
                JSONObject resource = resources.optJSONObject(index);
                if (resource == null) return false;
                String id = resource.optString("id", "");
                String expectedSha256 = resource.optString("sha256", "");
                String archive = archives.get(id);
                if (archive == null || expectedSha256.length() != 64 || actual.containsKey(id)) {
                    return false;
                }
                IntegrityEntry entry = digestIntegrityEntry(
                    new File(target, "product-payloads/" + archive), archive);
                if (!expectedSha256.equals(entry.sha256)) {
                    return false;
                }
                actual.put(id, expectedSha256);
            }
            return actual.keySet().equals(archives.keySet());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static List<IntegrityEntry> collectIntegrityEntries(File resourceDirectory) throws Exception {
        List<IntegrityEntry> entries = new ArrayList<>();
        for (String rootName : INTEGRITY_ROOTS) {
            File root = new File(resourceDirectory, rootName);
            if (!root.isDirectory() || Files.isSymbolicLink(root.toPath())) {
                throw new IOException("Missing or unsafe script root: " + rootName);
            }
            collectIntegrityEntries(resourceDirectory, root, entries);
        }
        entries.sort((left, right) -> left.path.compareTo(right.path));
        return entries;
    }

    private static void collectIntegrityEntries(File resourceDirectory,
                                                File current,
                                                List<IntegrityEntry> entries) throws Exception {
        File[] children = current.listFiles();
        if (children == null) {
            throw new IOException("Unable to list staged script directory");
        }
        Arrays.sort(children, (left, right) -> left.getName().compareTo(right.getName()));
        for (File child : children) {
            if (Files.isSymbolicLink(child.toPath())) {
                throw new IOException("Staged script tree contains a symbolic link");
            }
            if (child.isDirectory()) {
                collectIntegrityEntries(resourceDirectory, child, entries);
                continue;
            }
            if (!child.isFile()) {
                throw new IOException("Staged script tree contains a non-regular file");
            }
            String relativePath = resourceDirectory.toPath().relativize(child.toPath())
                .toString().replace(File.separatorChar, '/');
            if (shouldTrackIntegrity(relativePath)) {
                entries.add(digestIntegrityEntry(child, relativePath));
            }
        }
    }

    private static boolean shouldTrackIntegrity(String relativePath) {
        return relativePath.endsWith(".sh")
            || relativePath.startsWith("scripts-public/")
            || "bootstrap/scripts/openhouse-system".equals(relativePath);
    }

    private static IntegrityEntry digestIntegrityEntry(File file, String relativePath) throws Exception {
        MessageDigest digest = newSha256Digest();
        long size = 0L;
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                size += read;
            }
        }
        return new IntegrityEntry(relativePath, size, toHex(digest.digest()));
    }

    private static boolean verifyIntegrityEntries(File resourceDirectory, JSONArray expectedValues) {
        try {
            if (expectedValues == null || expectedValues.length() == 0) {
                return false;
            }
            Map<String, IntegrityEntry> expected = new LinkedHashMap<>();
            for (int index = 0; index < expectedValues.length(); index++) {
                JSONObject value = expectedValues.optJSONObject(index);
                if (value == null) {
                    return false;
                }
                String path = value.optString("path", "");
                IntegrityEntry entry = new IntegrityEntry(
                    path,
                    value.optLong("size", -1L),
                    value.optString("sha256", "")
                );
                if (path.isEmpty() || entry.size < 0L || entry.sha256.length() != 64
                    || expected.put(path, entry) != null) {
                    return false;
                }
            }

            Map<String, IntegrityEntry> actual = new LinkedHashMap<>();
            for (IntegrityEntry entry : collectIntegrityEntries(resourceDirectory)) {
                actual.put(entry.path, entry);
            }
            if (expected.size() != actual.size()
                || !actual.containsKey("bootstrap/bootstrap.sh")
                || !actual.containsKey("maintainer/install-runtime-components.sh")
                || !actual.containsKey("scripts-public/check-ai-tools.sh")) {
                return false;
            }
            for (Map.Entry<String, IntegrityEntry> item : expected.entrySet()) {
                IntegrityEntry actualEntry = actual.get(item.getKey());
                IntegrityEntry expectedEntry = item.getValue();
                if (actualEntry == null
                    || actualEntry.size != expectedEntry.size
                    || !actualEntry.sha256.equals(expectedEntry.sha256)) {
                    return false;
                }
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static MessageDigest newSha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 digest is unavailable", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return builder.toString();
    }

    private static void writeSyncedFile(File destination, byte[] bytes) throws Exception {
        try (FileOutputStream output = new FileOutputStream(destination, false)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
        setPrivateFile(destination);
    }

    private static void deleteOldAndroidVersionDirectories(File root, File current) throws Exception {
        File[] children = root.listFiles();
        if (children == null) {
            throw new IOException("Unable to list resource root for cleanup");
        }
        for (File child : children) {
            if (child.equals(current) || !isAndroidVersionDirectoryName(child.getName())) {
                continue;
            }
            requireDirectChild(root, child);
            if (!child.isDirectory() || Files.isSymbolicLink(child.toPath())) {
                continue;
            }
            deleteRecursivelyStrict(child);
        }
        syncDirectoryBestEffort(root);
    }

    private static boolean isAndroidVersionDirectoryName(String name) {
        return name != null
            && name.matches("apk-[A-Za-z0-9._-]+-[0-9]+-[0-9]{8}");
    }

    private static void deleteRecursivelyStrict(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) {
            if (!file.delete()) {
                throw new IOException("Unable to delete stale resource link");
            }
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursivelyStrict(child);
            }
        }
        if (!file.delete()) {
            throw new IOException("Unable to delete stale APK resource: " + file.getName());
        }
    }

    private static void moveDirectoryAtomically(File source, File destination) throws Exception {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("Atomic resource directory publication is unavailable", e);
        }
    }

    private static void moveFileAtomically(File source, File destination) throws Exception {
        try {
            Files.move(source.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("Atomic pending marker publication is unavailable", e);
        }
    }

    private static boolean isRegularFile(File file) {
        try {
            return file.isFile() && !Files.isSymbolicLink(file.toPath());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void ensurePrivateDirectory(File directory) throws Exception {
        if (directory.exists()) {
            if (!directory.isDirectory() || Files.isSymbolicLink(directory.toPath())) {
                throw new IOException("Unsafe resource directory");
            }
        } else if (!directory.mkdirs()) {
            throw new IOException("Unable to create private resource directory");
        }
        setPrivateDirectory(directory);
    }

    private static void ensurePrivatePath(File home, String relativePath) throws Exception {
        File current = home;
        for (String segment : relativePath.split("/")) {
            validatePrivatePathSegment(segment);
            current = new File(current, segment);
            ensurePrivateDirectory(current);
        }
    }

    private static void requireReadyHome(File home) throws Exception {
        if (!home.isDirectory() || Files.isSymbolicLink(home.toPath()) || !home.canWrite()) {
            throw new IOException("Termux HOME is not ready");
        }
    }

    private static void validatePrivatePathSegment(String segment) throws Exception {
        if (segment == null || !segment.matches("[A-Za-z0-9.][A-Za-z0-9._-]{0,127}")
            || ".".equals(segment) || "..".equals(segment)) {
            throw new IOException("Unsafe private resource path");
        }
    }

    private static void setPrivateDirectory(File directory) throws Exception {
        if (!directory.setReadable(false, false)
            || !directory.setWritable(false, false)
            || !directory.setExecutable(false, false)
            || !directory.setReadable(true, true)
            || !directory.setWritable(true, true)
            || !directory.setExecutable(true, true)) {
            throw new IOException("Unable to set private directory permissions");
        }
    }

    private static void setPrivateFile(File file) throws Exception {
        if (!file.setReadable(false, false)
            || !file.setWritable(false, false)
            || !file.setExecutable(false, false)
            || !file.setReadable(true, true)
            || !file.setWritable(true, true)) {
            throw new IOException("Unable to set private file permissions");
        }
    }

    private static void requireDirectChild(File parent, File child) throws Exception {
        File canonicalParent = parent.getCanonicalFile();
        File canonicalChildParent = child.getCanonicalFile().getParentFile();
        if (!canonicalParent.equals(canonicalChildParent)) {
            throw new IOException("Resource path escaped its parent");
        }
    }

    private static void validateAssetName(String name) throws Exception {
        if (name == null || !name.matches("[A-Za-z0-9._-][A-Za-z0-9._-]{0,127}")
            || ".".equals(name) || "..".equals(name)) {
            throw new IOException("Unsafe bundled resource name");
        }
    }

    private static void syncDirectoriesBestEffort(File directory) {
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && !Files.isSymbolicLink(child.toPath())) {
                    syncDirectoriesBestEffort(child);
                }
            }
        }
        syncDirectoryBestEffort(directory);
    }

    private static void syncDirectoryBestEffort(File directory) {
        try (FileChannel channel = FileChannel.open(directory.toPath(), StandardOpenOption.READ)) {
            channel.force(true);
        } catch (Exception ignored) {
        }
    }

    private static void deleteOwnedTemporary(File temporary, File root) {
        try {
            requireDirectChild(root, temporary);
            if (!temporary.getName().startsWith(".apk-")
                || !temporary.getName().contains(".android-tmp-")) {
                return;
            }
            deleteRecursivelyStrict(temporary);
        } catch (Exception ignored) {
        }
    }

    private static File expectedResourceDirectory(File home,
                                                  String versionName,
                                                  long versionCode,
                                                  long packageLastUpdateTime) {
        String directoryName = "apk-" + safeVersionName(versionName) + "-" + versionCode + "-"
            + utcDate(new Date(Math.max(0L, packageLastUpdateTime)));
        return new File(new File(home, ROOT_RELATIVE_PATH), directoryName);
    }

    private static String safeVersionName(String versionName) {
        String raw = versionName == null ? "" : versionName.trim();
        String safe = raw.replaceAll("[^A-Za-z0-9._-]+", "-")
            .replaceAll("^-+|-+$", "");
        if (safe.isEmpty() || ".".equals(safe) || "..".equals(safe)) {
            safe = "unknown";
        }
        return safe.length() <= 64 ? safe : safe.substring(0, 64);
    }

    private static String utcDate(Date value) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(value);
    }

    private static long packageVersionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ? info.getLongVersionCode() : info.versionCode;
    }

    private static String safeMessage(Exception error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty() ? "Resource delivery failed" : message;
    }

    interface AssetSource {
        String[] list(String path) throws IOException;
        InputStream open(String path) throws IOException;
    }

    private static final class AndroidAssetSource implements AssetSource {
        private final Context context;

        AndroidAssetSource(Context context) {
            this.context = context;
        }

        @Override
        public String[] list(String path) throws IOException {
            return context.getAssets().list(path);
        }

        @Override
        public InputStream open(String path) throws IOException {
            return context.getAssets().open(path);
        }
    }

    private static final class IntegrityEntry {
        final String path;
        final long size;
        final String sha256;

        IntegrityEntry(String path, long size, String sha256) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    public static final class Result {
        public final boolean success;
        public final File resourceDirectory;
        public final boolean reused;
        public final String output;

        private Result(boolean success, File resourceDirectory, boolean reused, String output) {
            this.success = success;
            this.resourceDirectory = resourceDirectory;
            this.reused = reused;
            this.output = output;
        }

        static Result success(File resourceDirectory, boolean reused) {
            return new Result(true, resourceDirectory, reused, AI_REQUEST_SENTENCE);
        }

        static Result failure(File resourceDirectory, String output) {
            return new Result(false, resourceDirectory, false, output);
        }

        public boolean isSuccess() {
            return success;
        }

        public String guide() {
            return AI_REQUEST_SENTENCE;
        }
    }
}
