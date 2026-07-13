package com.termux.app.openhouse.resources;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

@RunWith(RobolectricTestRunner.class)
public class OpenHouseBundledResourceDeliveryTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void stagesAllFourRootsPrivatelyAndPublishesPendingMarker() throws Exception {
        File home = readyHome();
        FakeAssets assets = completeAssets();
        long packageLastUpdateTime = utcMillis("20260713");

        OpenHouseBundledResourceDelivery.Result result =
            OpenHouseBundledResourceDelivery.deliverForTesting(
                home, " 1.2 beta/ ", 42L, packageLastUpdateTime,
                OpenHouseBundledResourceDelivery.Reason.APK_UPDATE, assets);

        assertTrue(result.output, result.isSuccess());
        assertFalse(result.reused);
        assertEquals("apk-1.2-beta-42-20260713", result.resourceDirectory.getName());
        assertEquals(OpenHouseBundledResourceDelivery.AI_REQUEST_SENTENCE, result.guide());
        assertArrayEquals(bytes("manifest"), Files.readAllBytes(
            new File(result.resourceDirectory, "product-payloads/manifest.json").toPath()));
        assertArrayEquals(bytes("nested"), Files.readAllBytes(
            new File(result.resourceDirectory, "product-payloads/nested/data.bin").toPath()));
        assertArrayEquals(bytes("bootstrap"), Files.readAllBytes(
            new File(result.resourceDirectory, "bootstrap/bootstrap.sh").toPath()));
        assertArrayEquals(bytes("ignore"), Files.readAllBytes(
            new File(result.resourceDirectory, "bootstrap/.gitignore").toPath()));
        assertArrayEquals(bytes("maintainer"), Files.readAllBytes(
            new File(result.resourceDirectory, "maintainer/install-runtime-components.sh").toPath()));
        assertArrayEquals(bytes("public"), Files.readAllBytes(
            new File(result.resourceDirectory, "scripts-public/check-ai-tools.sh").toPath()));
        assertTrue(new File(result.resourceDirectory, ".complete").isFile());
        assertPrivateDirectory(result.resourceDirectory);
        assertPrivateFile(new File(result.resourceDirectory, "AI_UPDATE_GUIDE.md"));
        assertPrivateFile(new File(result.resourceDirectory, "bootstrap/bootstrap.sh"));
        assertPrivateFile(new File(result.resourceDirectory, "product-payloads/manifest.json"));
        assertPrivateFile(new File(result.resourceDirectory, "product-payloads/nested/data.bin"));
        JSONObject complete = new JSONObject(new String(Files.readAllBytes(
            new File(result.resourceDirectory, ".complete").toPath()), StandardCharsets.UTF_8));
        assertEquals(3, complete.getInt("schemaVersion"));
        JSONArray verifiedFiles = complete.getJSONArray("verifiedFiles");
        assertTrue(hasVerifiedFile(verifiedFiles, "bootstrap/bootstrap.sh", bytes("bootstrap").length));
        assertTrue(hasVerifiedFile(verifiedFiles,
            "maintainer/install-runtime-components.sh", bytes("maintainer").length));
        assertTrue(hasVerifiedFile(verifiedFiles,
            "scripts-public/check-ai-tools.sh", bytes("public").length));

        File root = new File(home, OpenHouseBundledResourceDelivery.ROOT_RELATIVE_PATH);
        assertEquals("PENDING_APK_RESOURCES.json",
            OpenHouseBundledResourceDelivery.PENDING_MARKER_NAME);
        File pending = new File(root, OpenHouseBundledResourceDelivery.PENDING_MARKER_NAME);
        JSONObject value = new JSONObject(new String(
            Files.readAllBytes(pending.toPath()), StandardCharsets.UTF_8));
        assertEquals("1.2 beta/", value.getString("apkVersionName").trim());
        assertEquals(42L, value.getLong("apkVersionCode"));
        assertEquals("apk_update", value.getString("reason"));
        assertEquals("pending_ai", value.getString("status"));
        assertEquals("$HOME/" + OpenHouseBundledResourceDelivery.ROOT_RELATIVE_PATH
            + "/apk-1.2-beta-42-20260713", value.getString("resourceDir"));
        assertPrivateFile(pending);
        assertFalse(hasAndroidDeliveryTemporary(root));
    }

    @Test
    public void completeTargetIsIdempotentAndDoesNotReadAssetsTwice() throws Exception {
        File home = readyHome();
        FakeAssets assets = completeAssets();
        long packageLastUpdateTime = utcMillis("20260713");
        OpenHouseBundledResourceDelivery.Result first =
            OpenHouseBundledResourceDelivery.deliverForTesting(
                home, "2.0", 50L, packageLastUpdateTime,
                OpenHouseBundledResourceDelivery.Reason.APK_UPDATE, assets);
        assertTrue(first.isSuccess());
        int reads = assets.openCount;
        long completedAt = new File(first.resourceDirectory, ".complete").lastModified();

        OpenHouseBundledResourceDelivery.Result second =
            OpenHouseBundledResourceDelivery.deliverForTesting(
                home, "2.0", 50L, packageLastUpdateTime,
                OpenHouseBundledResourceDelivery.Reason.APK_UPDATE, assets);

        assertTrue(second.isSuccess());
        assertTrue(second.reused);
        assertEquals(reads, assets.openCount);
        assertEquals(completedAt, new File(second.resourceDirectory, ".complete").lastModified());
    }

    @Test
    public void failureCreatesNoPendingAndKeepsUnrelatedDirectoriesWhileRemovingOwnTemporary() throws Exception {
        File home = readyHome();
        File root = new File(home, OpenHouseBundledResourceDelivery.ROOT_RELATIVE_PATH);
        assertTrue(root.mkdirs());
        chmod(root, 0700);
        File userDrop = new File(root, "minor-user-drop");
        assertTrue(userDrop.mkdir());
        File unrelatedTemporary = new File(root, ".unrelated.android-tmp-preserve");
        assertTrue(unrelatedTemporary.mkdir());
        FakeAssets assets = completeAssets();
        assets.failurePath = "openhouse/product-payloads/nested/data.bin";

        OpenHouseBundledResourceDelivery.Result result =
            OpenHouseBundledResourceDelivery.deliverForTesting(
                home, "3.0", 60L, utcMillis("20260713"),
                OpenHouseBundledResourceDelivery.Reason.FIRST_INSTALL, assets);

        assertFalse(result.isSuccess());
        assertTrue(userDrop.isDirectory());
        assertTrue(unrelatedTemporary.isDirectory());
        assertFalse(new File(root, OpenHouseBundledResourceDelivery.PENDING_MARKER_NAME).exists());
        assertFalse(new File(root, "apk-3.0-60-20260713").exists());
        assertFalse(hasAndroidDeliveryTemporary(root));
    }

    @Test
    public void missingHomeDoesNotCreateResourceRootOrPendingMarker() throws Exception {
        File home = new File(temporaryFolder.getRoot(), "missing-home");
        OpenHouseBundledResourceDelivery.Result result =
            OpenHouseBundledResourceDelivery.deliverForTesting(
                home, "1.0", 1L, utcMillis("20260713"),
                OpenHouseBundledResourceDelivery.Reason.FIRST_INSTALL, completeAssets());

        assertFalse(result.isSuccess());
        assertFalse(home.exists());
    }

    @Test
    public void failedRetryUsesStablePackageUpdateDateAndNeverPublishesNewMarker() throws Exception {
        File home = readyHome();
        long stableLastUpdateTime = utcMillis("20260102");
        FakeAssets failing = completeAssets();
        failing.failurePath = "openhouse/product-payloads/core.tar";

        OpenHouseBundledResourceDelivery.Result failed =
            OpenHouseBundledResourceDelivery.deliverForTesting(
                home, "4.0", 70L, stableLastUpdateTime,
                OpenHouseBundledResourceDelivery.Reason.APK_UPDATE, failing);
        File root = new File(home, OpenHouseBundledResourceDelivery.ROOT_RELATIVE_PATH);
        assertFalse(failed.isSuccess());
        assertEquals("apk-4.0-70-20260102", failed.resourceDirectory.getName());
        assertFalse(new File(root, OpenHouseBundledResourceDelivery.PENDING_MARKER_NAME).exists());

        OpenHouseBundledResourceDelivery.Result retried =
            OpenHouseBundledResourceDelivery.deliverForTesting(
                home, "4.0", 70L, stableLastUpdateTime,
                OpenHouseBundledResourceDelivery.Reason.APK_UPDATE, completeAssets());
        assertTrue(retried.isSuccess());
        assertEquals("apk-4.0-70-20260102", retried.resourceDirectory.getName());
    }

    @Test
    public void copyFailurePreservesMarkerFromPreviousApk() throws Exception {
        File home = readyHome();
        File root = new File(home, OpenHouseBundledResourceDelivery.ROOT_RELATIVE_PATH);
        assertTrue(root.mkdirs());
        chmod(root, 0700);
        File marker = new File(root, OpenHouseBundledResourceDelivery.PENDING_MARKER_NAME);
        byte[] previous = bytes("{\"apkVersionCode\":69}\n");
        Files.write(marker.toPath(), previous);
        chmod(marker, 0600);
        FakeAssets failing = completeAssets();
        failing.failurePath = "openhouse/product-payloads/core.tar";

        OpenHouseBundledResourceDelivery.Result result =
            OpenHouseBundledResourceDelivery.deliverForTesting(
                home, "4.1", 71L, utcMillis("20260103"),
                OpenHouseBundledResourceDelivery.Reason.APK_UPDATE, failing);

        assertFalse(result.isSuccess());
        assertArrayEquals(previous, Files.readAllBytes(marker.toPath()));
    }

    @Test
    public void pendingMarkerClearIsExactAndRejectsSymlink() throws Exception {
        File home = readyHome();
        File root = new File(home, OpenHouseBundledResourceDelivery.ROOT_RELATIVE_PATH);
        assertTrue(root.mkdirs());
        chmod(root, 0700);
        File marker = new File(root, OpenHouseBundledResourceDelivery.PENDING_MARKER_NAME);
        Files.write(marker.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        chmod(marker, 0600);
        assertTrue(OpenHouseBundledResourceDelivery.clearPendingAiUpdateMarker(root));
        assertFalse(marker.exists());

        File outside = new File(temporaryFolder.getRoot(), "outside-marker");
        Files.write(outside.toPath(), "keep".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(marker.toPath(), outside.toPath());
        assertFalse(OpenHouseBundledResourceDelivery.clearPendingAiUpdateMarker(root));
        assertTrue(outside.isFile());
    }

    @Test
    public void firstInstallMarkerClearRequiresMatchingVersionAndReason() throws Exception {
        File home = readyHome();
        OpenHouseBundledResourceDelivery.Result result =
            OpenHouseBundledResourceDelivery.deliverForTesting(
                home, "5.0", 80L, utcMillis("20260713"),
                OpenHouseBundledResourceDelivery.Reason.FIRST_INSTALL, completeAssets());
        assertTrue(result.output, result.isSuccess());

        File root = new File(home, OpenHouseBundledResourceDelivery.ROOT_RELATIVE_PATH);
        File marker = new File(root, OpenHouseBundledResourceDelivery.PENDING_MARKER_NAME);
        assertFalse(OpenHouseBundledResourceDelivery.clearPendingAiUpdateMarker(
            root, 81L, OpenHouseBundledResourceDelivery.Reason.FIRST_INSTALL));
        assertTrue(marker.isFile());
        assertFalse(OpenHouseBundledResourceDelivery.clearPendingAiUpdateMarker(
            root, 80L, OpenHouseBundledResourceDelivery.Reason.APK_UPDATE));
        assertTrue(marker.isFile());
        assertTrue(OpenHouseBundledResourceDelivery.clearPendingAiUpdateMarker(
            root, 80L, OpenHouseBundledResourceDelivery.Reason.FIRST_INSTALL));
        assertFalse(marker.exists());
    }

    @Test
    public void newApkDeletesOnlyOlderAndroidVersionDirectories() throws Exception {
        File home = readyHome();
        File root = new File(home, OpenHouseBundledResourceDelivery.ROOT_RELATIVE_PATH);
        assertTrue(root.mkdirs());
        chmod(root, 0700);
        File oldAndroid = new File(root, "apk-1.0-10-20260101");
        assertTrue(oldAndroid.mkdir());
        Files.write(new File(oldAndroid, "old").toPath(), bytes("old"));
        File minor = new File(root, "minor-user-drop");
        assertTrue(minor.mkdir());
        File similarUserDirectory = new File(root, "apk-user-not-android");
        assertTrue(similarUserDirectory.mkdir());

        OpenHouseBundledResourceDelivery.Result result =
            OpenHouseBundledResourceDelivery.deliverForTesting(
                home, "6.0", 90L, utcMillis("20260713"),
                OpenHouseBundledResourceDelivery.Reason.APK_UPDATE, completeAssets());

        assertTrue(result.output, result.isSuccess());
        assertFalse(oldAndroid.exists());
        assertTrue(minor.isDirectory());
        assertTrue(similarUserDirectory.isDirectory());
    }

    @Test
    public void completeVerificationRejectsSymlinkedCriticalScript() throws Exception {
        File home = readyHome();
        OpenHouseBundledResourceDelivery.Result result =
            OpenHouseBundledResourceDelivery.deliverForTesting(
                home, "7.0", 100L, utcMillis("20260713"),
                OpenHouseBundledResourceDelivery.Reason.FIRST_INSTALL, completeAssets());
        assertTrue(result.output, result.isSuccess());
        File bootstrap = new File(result.resourceDirectory, "bootstrap/bootstrap.sh");
        File outside = new File(home, "outside-bootstrap.sh");
        Files.write(outside.toPath(), bytes("outside"));
        assertTrue(bootstrap.delete());
        Files.createSymbolicLink(bootstrap.toPath(), outside.toPath());

        assertFalse(OpenHouseBundledResourceDelivery.isCompleteTarget(
            result.resourceDirectory, 100L));
    }

    @Test
    public void verifyExistingDoesNotChangePendingMarker() throws Exception {
        File home = readyHome();
        OpenHouseBundledResourceDelivery.Result staged =
            OpenHouseBundledResourceDelivery.deliverForTesting(
                home, "8.0", 110L, utcMillis("20260713"),
                OpenHouseBundledResourceDelivery.Reason.APK_UPDATE, completeAssets());
        assertTrue(staged.output, staged.isSuccess());
        File marker = new File(new File(home, OpenHouseBundledResourceDelivery.ROOT_RELATIVE_PATH),
            OpenHouseBundledResourceDelivery.PENDING_MARKER_NAME);
        byte[] before = Files.readAllBytes(marker.toPath());
        long modified = marker.lastModified();

        OpenHouseBundledResourceDelivery.Result verified =
            OpenHouseBundledResourceDelivery.verifyExistingForTesting(
                home, "8.0", 110L, utcMillis("20260713"));

        assertTrue(verified.output, verified.isSuccess());
        assertTrue(verified.reused);
        assertArrayEquals(before, Files.readAllBytes(marker.toPath()));
        assertEquals(modified, marker.lastModified());
    }

    @Test
    public void truncatedScriptFailsVerificationAndExplicitStageRestoresIt() throws Exception {
        File home = readyHome();
        OpenHouseBundledResourceDelivery.Result staged =
            OpenHouseBundledResourceDelivery.deliverForTesting(
                home, "9.0", 120L, utcMillis("20260713"),
                OpenHouseBundledResourceDelivery.Reason.FIRST_INSTALL, completeAssets());
        assertTrue(staged.output, staged.isSuccess());
        File secondaryScript = new File(staged.resourceDirectory, "maintainer/repair-smallphone.sh");
        Files.write(secondaryScript.toPath(), bytes("cut"));

        OpenHouseBundledResourceDelivery.Result rejected =
            OpenHouseBundledResourceDelivery.verifyExistingForTesting(
                home, "9.0", 120L, utcMillis("20260713"));
        assertFalse(rejected.isSuccess());

        OpenHouseBundledResourceDelivery.Result restored =
            OpenHouseBundledResourceDelivery.deliverForTesting(
                home, "9.0", 120L, utcMillis("20260713"),
                OpenHouseBundledResourceDelivery.Reason.FIRST_INSTALL, completeAssets());
        assertTrue(restored.output, restored.isSuccess());
        assertFalse(restored.reused);
        assertArrayEquals(bytes("repair"), Files.readAllBytes(secondaryScript.toPath()));
        assertTrue(OpenHouseBundledResourceDelivery.verifyExistingForTesting(
            home, "9.0", 120L, utcMillis("20260713")).isSuccess());
    }

    private File readyHome() throws Exception {
        File home = temporaryFolder.newFolder("home-" + System.nanoTime());
        chmod(home, 0700);
        return home;
    }

    private static FakeAssets completeAssets() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("smallphoneai/bootstrap/bootstrap.sh", bytes("bootstrap"));
        files.put("smallphoneai/bootstrap/.gitignore", bytes("ignore"));
        files.put("smallphoneai/bootstrap/scripts/00-check-termux.sh", bytes("check"));
        files.put("smallphoneai/bootstrap/scripts/50-install-runtime-components.sh", bytes("components"));
        files.put("smallphoneai/bootstrap/scripts/60-start-smallphone.sh", bytes("start"));
        files.put("maintainer/install-runtime-components.sh", bytes("maintainer"));
        files.put("maintainer/repair-smallphone.sh", bytes("repair"));
        files.put("openhouse/scripts-public/check-ai-tools.sh", bytes("public"));
        files.put("openhouse/product-payloads/manifest.json", bytes("manifest"));
        files.put("openhouse/product-payloads/payload-manifest.json", bytes("payload manifest"));
        files.put("openhouse/product-payloads/AI_UPDATE_GUIDE.md",
            bytes(OpenHouseBundledResourceDelivery.AI_REQUEST_SENTENCE));
        files.put("openhouse/product-payloads/core.tar", bytes("tar"));
        files.put("openhouse/product-payloads/nested/data.bin", bytes("nested"));
        return new FakeAssets(files);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean hasVerifiedFile(JSONArray values, String path, long size) {
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value != null
                && path.equals(value.optString("path", ""))
                && size == value.optLong("size", -1L)
                && value.optString("sha256", "").length() == 64) {
                return true;
            }
        }
        return false;
    }

    private static long utcMillis(String value) throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.parse(value).getTime();
    }

    private static boolean hasAndroidDeliveryTemporary(File root) {
        File[] children = root.listFiles((directory, name) ->
            name.startsWith(".apk-") && name.contains(".android-tmp-"));
        return children != null && children.length > 0;
    }

    private static void assertPrivateFile(File file) throws Exception {
        assertTrue(file.isFile());
        Set<PosixFilePermission> expected = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertEquals(expected, Files.getPosixFilePermissions(file.toPath()));
    }

    private static void assertPrivateDirectory(File directory) throws Exception {
        Set<PosixFilePermission> expected = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
        assertEquals(expected, Files.getPosixFilePermissions(directory.toPath()));
    }

    private static void chmod(File file, int mode) throws Exception {
        Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        if ((mode & 0400) != 0) permissions.add(PosixFilePermission.OWNER_READ);
        if ((mode & 0200) != 0) permissions.add(PosixFilePermission.OWNER_WRITE);
        if ((mode & 0100) != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE);
        if ((mode & 0040) != 0) permissions.add(PosixFilePermission.GROUP_READ);
        if ((mode & 0020) != 0) permissions.add(PosixFilePermission.GROUP_WRITE);
        if ((mode & 0010) != 0) permissions.add(PosixFilePermission.GROUP_EXECUTE);
        if ((mode & 0004) != 0) permissions.add(PosixFilePermission.OTHERS_READ);
        if ((mode & 0002) != 0) permissions.add(PosixFilePermission.OTHERS_WRITE);
        if ((mode & 0001) != 0) permissions.add(PosixFilePermission.OTHERS_EXECUTE);
        Files.setPosixFilePermissions(file.toPath(), permissions);
    }

    private static final class FakeAssets implements OpenHouseBundledResourceDelivery.AssetSource {
        private final Map<String, byte[]> files;
        String failurePath;
        int openCount;

        FakeAssets(Map<String, byte[]> files) {
            this.files = files;
        }

        @Override
        public String[] list(String assetPath) {
            String prefix = assetPath + "/";
            List<String> children = new ArrayList<>();
            for (String file : files.keySet()) {
                if (!file.startsWith(prefix)) continue;
                String remainder = file.substring(prefix.length());
                String child = remainder.contains("/")
                    ? remainder.substring(0, remainder.indexOf('/')) : remainder;
                if (!children.contains(child)) children.add(child);
            }
            return children.toArray(new String[0]);
        }

        @Override
        public InputStream open(String assetPath) throws IOException {
            openCount++;
            if (assetPath.equals(failurePath)) throw new IOException("fixture failure");
            byte[] value = files.get(assetPath);
            if (value == null) throw new IOException("missing fixture asset: " + assetPath);
            return new ByteArrayInputStream(value);
        }
    }
}
