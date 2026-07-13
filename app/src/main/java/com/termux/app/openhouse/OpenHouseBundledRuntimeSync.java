package com.termux.app.openhouse;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.termux.app.openhouse.resources.OpenHouseBundledResourceDelivery;
import com.termux.shared.logger.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Adapts the unified APK resource directory for first-install and maintenance callers. */
public final class OpenHouseBundledRuntimeSync {

    private static final String LOG_TAG = "OpenHouseRuntimeSync";

    private OpenHouseBundledRuntimeSync() {
    }

    /** Reuses the current APK resource directory without changing its pending marker. */
    public static Result sync(Context context) throws IOException {
        return prepareExisting(context);
    }

    /** Stages the current APK resources and publishes the caller's explicit marker reason. */
    public static Result sync(Context context, OpenHouseBundledResourceDelivery.Reason reason)
        throws IOException {
        Context appContext = requireContext(context);
        PackageInfo packageInfo = getPackageInfo(appContext);
        if (reason == null) {
            throw new IOException("Cannot stage APK runtime assets without an explicit reason");
        }
        OpenHouseBundledResourceDelivery.Result delivery = OpenHouseBundledResourceDelivery.deliver(
            appContext, packageInfo, reason);
        return buildResult(appContext, delivery);
    }

    /** Verifies and returns the current APK resource directory without writing any file. */
    public static Result prepareExisting(Context context) throws IOException {
        Context appContext = requireContext(context);
        PackageInfo packageInfo = getPackageInfo(appContext);
        return buildResult(appContext, OpenHouseBundledResourceDelivery.verifyExisting(packageInfo));
    }

    private static Context requireContext(Context context) throws IOException {
        if (context == null) {
            throw new IOException("Cannot prepare APK runtime assets: context is null");
        }
        return context.getApplicationContext();
    }

    private static PackageInfo getPackageInfo(Context appContext) throws IOException {
        try {
            return appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("Cannot read APK version for resource staging", e);
        }
    }

    private static Result buildResult(Context appContext,
                                      OpenHouseBundledResourceDelivery.Result delivery)
        throws IOException {
        if (!delivery.isSuccess() || delivery.resourceDirectory == null) {
            throw new IOException("Cannot prepare APK runtime assets: " + delivery.output);
        }

        File resourceDir = delivery.resourceDirectory;
        File bootstrapFile = new File(resourceDir, "bootstrap/bootstrap.sh");
        File payloadDir = new File(resourceDir, "product-payloads");
        File maintainerDir = new File(resourceDir, "maintainer");
        File scriptsPublicDir = new File(resourceDir, "scripts-public");
        File guideFile = new File(resourceDir, "AI_UPDATE_GUIDE.md");
        File payloadManifest = new File(payloadDir, "manifest.json");
        File completeMarker = new File(resourceDir, ".complete");
        requireRegularFile(bootstrapFile, "bootstrap/bootstrap.sh");
        requireRegularFile(payloadManifest, "product-payloads/manifest.json");
        requireDirectory(maintainerDir, "maintainer");
        requireDirectory(scriptsPublicDir, "scripts-public");
        requireRegularFile(guideFile, "AI_UPDATE_GUIDE.md");
        requireRegularFile(completeMarker, ".complete");

        int assetCount = countRegularFiles(resourceDir);
        String resourceVersion = resourceDir.getName();
        String runtimeReport = buildRuntimeReport(
            appContext, resourceDir, bootstrapFile, payloadManifest, assetCount, delivery.reused);
        Result result = new Result(
            resourceDir,
            bootstrapFile,
            payloadDir,
            maintainerDir,
            scriptsPublicDir,
            guideFile,
            completeMarker,
            assetCount,
            resourceVersion,
            runtimeReport
        );
        Logger.logInfo(LOG_TAG, "Prepared unified APK runtime resources: " + result.toLogString());
        return result;
    }

    private static void requireDirectory(File directory, String label) throws IOException {
        if (!directory.isDirectory() || Files.isSymbolicLink(directory.toPath())) {
            throw new IOException("Staged APK resource directory is missing or unsafe: " + label);
        }
    }

    private static void requireRegularFile(File file, String label) throws IOException {
        if (!file.isFile() || Files.isSymbolicLink(file.toPath())) {
            throw new IOException("Staged APK resource is missing or unsafe: " + label);
        }
    }

    private static int countRegularFiles(File root) throws IOException {
        if (Files.isSymbolicLink(root.toPath())) {
            throw new IOException("Staged APK resource directory is unsafe");
        }
        File[] children = root.listFiles();
        if (children == null) {
            throw new IOException("Cannot list staged APK resource directory");
        }
        int count = 0;
        for (File child : children) {
            if (Files.isSymbolicLink(child.toPath())) {
                throw new IOException("Staged APK resource contains a symbolic link: " + child.getName());
            }
            count += child.isDirectory() ? countRegularFiles(child) : (child.isFile() ? 1 : 0);
        }
        return count;
    }

    private static String buildRuntimeReport(Context context,
                                             File resourceDir,
                                             File bootstrapFile,
                                             File payloadManifest,
                                             int assetCount,
                                             boolean reused) {
        return "OpenHouse unified APK resources:\n"
            + "package=" + context.getPackageName() + "\n"
            + "resourceDir=" + resourceDir.getAbsolutePath() + "\n"
            + "bootstrap=" + bootstrapFile.getAbsolutePath() + "\n"
            + "payloadManifest=" + payloadManifest.getAbsolutePath() + "\n"
            + "assetCount=" + assetCount + "\n"
            + "staging=" + (reused ? "reused" : "published") + "\n"
            + "compatibility=staged-for-runtime-review\n";
    }

    public static final class Result {
        public final File resourceDir;
        public final File bootstrapFile;
        public final File payloadDir;
        public final File maintainerDir;
        public final File scriptsPublicDir;
        public final File guideFile;
        public final File markerFile;
        public final int assetCount;
        public final String treeHash;
        public final String runtimeReport;

        Result(File resourceDir,
               File bootstrapFile,
               File payloadDir,
               File maintainerDir,
               File scriptsPublicDir,
               File guideFile,
               File markerFile,
               int assetCount,
               String treeHash,
               String runtimeReport) {
            this.resourceDir = resourceDir;
            this.bootstrapFile = bootstrapFile;
            this.payloadDir = payloadDir;
            this.maintainerDir = maintainerDir;
            this.scriptsPublicDir = scriptsPublicDir;
            this.guideFile = guideFile;
            this.markerFile = markerFile;
            this.assetCount = assetCount;
            this.treeHash = treeHash;
            this.runtimeReport = runtimeReport == null ? "" : runtimeReport;
        }

        public String toLogString() {
            return assetCount + " assets"
                + ", resource_version=" + treeHash
                + ", bootstrap=" + bootstrapFile.getAbsolutePath()
                + ", payloads=" + payloadDir.getAbsolutePath()
                + ", complete=" + markerFile.getAbsolutePath();
        }
    }
}
