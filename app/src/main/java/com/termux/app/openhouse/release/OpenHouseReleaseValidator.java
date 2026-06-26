package com.termux.app.openhouse.release;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import java.io.File;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class OpenHouseReleaseValidator {

    private OpenHouseReleaseValidator() {
    }

    public static ManifestValidationResult validateManifest(Context context, OpenHouseReleaseManifest manifest)
        throws OpenHouseReleaseException {
        return validateManifest(context, manifest, OpenHouseReleaseSettings.getChannel(context));
    }

    public static ManifestValidationResult validateManifest(Context context, OpenHouseReleaseManifest manifest, String expectedChannel)
        throws OpenHouseReleaseException {
        String currentPackageName = context.getPackageName();
        if (!currentPackageName.equals(manifest.packageName)) {
            throw new OpenHouseReleaseException("发布包名不一致: manifest="
                + manifest.packageName + "，当前应用=" + currentPackageName);
        }
        String normalizedExpectedChannel = expectedChannel == null || expectedChannel.trim().isEmpty()
            ? "stable"
            : expectedChannel.trim();
        if (!normalizedExpectedChannel.equals(manifest.channel)) {
            throw new OpenHouseReleaseException("发布渠道不一致: manifest="
                + manifest.channel + "，当前配置=" + normalizedExpectedChannel);
        }

        PackageInfo currentPackageInfo = getCurrentPackageInfo(context, true);
        long currentVersionCode = getVersionCode(currentPackageInfo);
        if (manifest.latestVersionCode <= currentVersionCode) {
            return new ManifestValidationResult(
                false,
                currentVersionCode,
                currentPackageInfo.versionName,
                "发布版本不高于当前版本，已拒绝下载。当前 versionCode="
                    + currentVersionCode + "，发布 versionCode=" + manifest.latestVersionCode
            );
        }

        if (manifest.hasSigningCertificatePin()) {
            Set<String> installedCertificates = getSigningCertificateSha256s(currentPackageInfo);
            if (!installedCertificates.contains(manifest.signingCertificateSha256)) {
                throw new OpenHouseReleaseException("manifest 签名证书指纹与当前应用不一致");
            }
        }

        return new ManifestValidationResult(
            true,
            currentVersionCode,
            currentPackageInfo.versionName,
            "发现可安装的新版本"
        );
    }

    public static DownloadedApkInfo validateDownloadedApk(Context context, OpenHouseReleaseManifest manifest, File apkFile)
        throws OpenHouseReleaseException {
        PackageManager packageManager = context.getPackageManager();
        PackageInfo archivePackageInfo = packageManager.getPackageArchiveInfo(apkFile.getAbsolutePath(), getSignatureFlags());
        if (archivePackageInfo == null) {
            throw new OpenHouseReleaseException("下载文件不是有效 APK");
        }
        if (archivePackageInfo.applicationInfo != null) {
            archivePackageInfo.applicationInfo.sourceDir = apkFile.getAbsolutePath();
            archivePackageInfo.applicationInfo.publicSourceDir = apkFile.getAbsolutePath();
        }

        String currentPackageName = context.getPackageName();
        if (!currentPackageName.equals(archivePackageInfo.packageName)) {
            throw new OpenHouseReleaseException("APK 包名不一致: APK="
                + archivePackageInfo.packageName + "，当前应用=" + currentPackageName);
        }
        if (!manifest.packageName.equals(archivePackageInfo.packageName)) {
            throw new OpenHouseReleaseException("APK 包名与 manifest 不一致: APK="
                + archivePackageInfo.packageName + "，manifest=" + manifest.packageName);
        }

        PackageInfo currentPackageInfo = getCurrentPackageInfo(context, true);
        long currentVersionCode = getVersionCode(currentPackageInfo);
        long archiveVersionCode = getVersionCode(archivePackageInfo);
        if (archiveVersionCode <= currentVersionCode) {
            throw new OpenHouseReleaseException("APK versionCode 不高于当前版本，已拒绝安装。当前="
                + currentVersionCode + "，APK=" + archiveVersionCode);
        }
        if (archiveVersionCode != manifest.latestVersionCode) {
            throw new OpenHouseReleaseException("APK versionCode 与 manifest 不一致: APK="
                + archiveVersionCode + "，manifest=" + manifest.latestVersionCode);
        }

        Set<String> currentCertificates = getSigningCertificateSha256s(currentPackageInfo);
        Set<String> archiveCertificates = getSigningCertificateSha256s(archivePackageInfo);
        if (!currentCertificates.equals(archiveCertificates)) {
            throw new OpenHouseReleaseException("APK 签名证书与当前应用不一致");
        }
        if (manifest.hasSigningCertificatePin() && !archiveCertificates.contains(manifest.signingCertificateSha256)) {
            throw new OpenHouseReleaseException("APK 签名证书与 manifest 指纹不一致");
        }

        return new DownloadedApkInfo(
            archivePackageInfo.packageName,
            archiveVersionCode,
            archivePackageInfo.versionName,
            archiveCertificates
        );
    }

    private static PackageInfo getCurrentPackageInfo(Context context, boolean includeSignatures) throws OpenHouseReleaseException {
        try {
            return context.getPackageManager().getPackageInfo(
                context.getPackageName(),
                includeSignatures ? getSignatureFlags() : 0
            );
        } catch (PackageManager.NameNotFoundException e) {
            throw new OpenHouseReleaseException("无法读取当前应用包信息", e);
        }
    }

    private static int getSignatureFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return PackageManager.GET_SIGNING_CERTIFICATES;
        }
        return PackageManager.GET_SIGNATURES;
    }

    private static long getVersionCode(PackageInfo packageInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return packageInfo.getLongVersionCode();
        }
        return packageInfo.versionCode;
    }

    private static Set<String> getSigningCertificateSha256s(PackageInfo packageInfo) throws OpenHouseReleaseException {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            SigningInfo signingInfo = packageInfo.signingInfo;
            if (signingInfo == null) {
                throw new OpenHouseReleaseException("无法读取 APK 签名信息");
            }
            signatures = signingInfo.getApkContentsSigners();
        } else {
            signatures = packageInfo.signatures;
        }

        if (signatures == null || signatures.length == 0) {
            throw new OpenHouseReleaseException("APK 没有可读取的签名证书");
        }

        LinkedHashSet<String> certificates = new LinkedHashSet<>();
        for (Signature signature : signatures) {
            certificates.add(sha256(signature.toByteArray()));
        }
        return Collections.unmodifiableSet(certificates);
    }

    private static String sha256(byte[] bytes) throws OpenHouseReleaseException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format(Locale.US, "%02x", value & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new OpenHouseReleaseException("系统缺少 SHA-256 支持", e);
        }
    }

    public static final class ManifestValidationResult {
        public final boolean updateAvailable;
        public final long currentVersionCode;
        public final String currentVersionName;
        public final String message;

        ManifestValidationResult(boolean updateAvailable, long currentVersionCode, String currentVersionName, String message) {
            this.updateAvailable = updateAvailable;
            this.currentVersionCode = currentVersionCode;
            this.currentVersionName = currentVersionName;
            this.message = message;
        }
    }

    public static final class DownloadedApkInfo {
        public final String packageName;
        public final long versionCode;
        public final String versionName;
        public final Set<String> signingCertificateSha256s;

        DownloadedApkInfo(String packageName, long versionCode, String versionName, Set<String> signingCertificateSha256s) {
            this.packageName = packageName;
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.signingCertificateSha256s = signingCertificateSha256s;
        }
    }
}
