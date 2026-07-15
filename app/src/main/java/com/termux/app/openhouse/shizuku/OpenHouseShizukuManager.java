package com.termux.app.openhouse.shizuku;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.system.Os;

import com.termux.app.openhouse.release.OpenHouseReleaseInstaller;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import rikka.shizuku.Shizuku;

public final class OpenHouseShizukuManager {

    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final String ASSET_ROOT = "third-party/shizuku/";
    private static final String APK_ASSET = ASSET_ROOT + "shizuku.apk";
    private static final String RISH_ASSET = ASSET_ROOT + "rish";
    private static final String RISH_DEX_ASSET = ASSET_ROOT + "rish_shizuku.dex";
    private static final int REQUEST_CODE = 47021;

    private final Context context;
    private final Runnable onChanged;
    private boolean started;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::notifyChanged;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::notifyChanged;
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
        (requestCode, grantResult) -> {
            if (requestCode == REQUEST_CODE) {
                notifyChanged();
            }
        };

    public OpenHouseShizukuManager(Context context, Runnable onChanged) {
        this.context = context;
        this.onChanged = onChanged;
    }

    public void start() {
        if (started) return;
        started = true;
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);
        notifyChanged();
    }

    public void stop() {
        if (!started) return;
        started = false;
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
    }

    public Snapshot snapshot() {
        PackageInfo packageInfo = getShizukuPackageInfo();
        boolean installed = packageInfo != null;
        boolean running = false;
        boolean authorized = false;
        int uid = -1;

        try {
            running = Shizuku.pingBinder();
            if (running) {
                uid = Shizuku.getUid();
                authorized = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            }
        } catch (Throwable ignored) {
            running = false;
            authorized = false;
            uid = -1;
        }

        String version = packageInfo == null ? null : packageInfo.versionName;
        boolean rishReady = isRishReady();
        String statusLabel;
        if (!installed) {
            statusLabel = "未安装";
        } else if (!running) {
            statusLabel = "已安装，服务未启动";
        } else if (!authorized) {
            statusLabel = "服务已运行，等待授权";
        } else {
            statusLabel = "已连接并授权";
        }
        return new Snapshot(installed, running, authorized, uid, version, rishReady, statusLabel);
    }

    public boolean installBundledShizuku() {
        File outputDir = new File(context.getCacheDir(), "shizuku");
        File apkFile = new File(outputDir, "shizuku.apk");
        try {
            copyAsset(APK_ASSET, apkFile, false);
        } catch (IOException e) {
            throw new IllegalStateException("无法准备内置 Shizuku APK", e);
        }

        Intent intent;
        if (!OpenHouseReleaseInstaller.canRequestPackageInstalls(context)) {
            intent = OpenHouseReleaseInstaller.createUnknownSourcesSettingsIntent(context);
        } else {
            intent = OpenHouseReleaseInstaller.createInstallIntent(context, apkFile);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        return true;
    }

    public boolean openShizuku() {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
        if (intent == null) {
            throw new IllegalStateException("没有找到 Shizuku 应用");
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        return true;
    }

    public void requestPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                throw new IllegalStateException("请先在 Shizuku 中启动服务");
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                notifyChanged();
                return;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                throw new IllegalStateException("授权曾被拒绝，请在 Shizuku 的授权应用管理中重新允许");
            }
            Shizuku.requestPermission(REQUEST_CODE);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("无法请求 Shizuku 授权", e);
        }
    }

    public boolean ensureRishInstalled() {
        File binDir = new File(context.getFilesDir(), "usr/bin");
        if (!new File(binDir, "bash").isFile()) {
            return false;
        }
        File rishFile = new File(binDir, "rish");
        File dexFile = new File(binDir, "rish_shizuku.dex");
        try {
            String script = readAssetText(RISH_ASSET)
                .replace("RISH_APPLICATION_ID=\"PKG\"", "RISH_APPLICATION_ID=\"com.termux\"")
                .replace(
                    "/system/bin/app_process -Djava.class.path=\"$DEX\" /system/bin --nice-name=rish "
                        + "rikka.shizuku.shell.ShizukuShellLoader \"$@\"",
                    "unset LD_LIBRARY_PATH LD_PRELOAD\n"
                        + "exec /system/bin/app_process -Djava.class.path=\"$DEX\" /system/bin --nice-name=rish "
                        + "rikka.shizuku.shell.ShizukuShellLoader \"$@\"");
            writeBytes(rishFile, script.getBytes(StandardCharsets.UTF_8));
            copyAsset(RISH_DEX_ASSET, dexFile, false);
            Os.chmod(rishFile.getAbsolutePath(), 0755);
            Os.chmod(dexFile.getAbsolutePath(), 0400);
            notifyChanged();
            return true;
        } catch (Exception e) {
            throw new IllegalStateException("无法准备终端 rish", e);
        }
    }

    private PackageInfo getShizukuPackageInfo() {
        try {
            return context.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private boolean isRishReady() {
        File binDir = new File(context.getFilesDir(), "usr/bin");
        File rishFile = new File(binDir, "rish");
        File dexFile = new File(binDir, "rish_shizuku.dex");
        return rishFile.isFile() && rishFile.canExecute() && dexFile.isFile() && dexFile.length() > 0;
    }

    private String readAssetText(String assetName) throws IOException {
        try (InputStream input = context.getAssets().open(assetName);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void copyAsset(String assetName, File target, boolean unused) throws IOException {
        try (InputStream input = context.getAssets().open(assetName)) {
            writeStream(target, input);
        }
    }

    private void writeBytes(File target, byte[] data) throws IOException {
        ensureParent(target);
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(data);
            output.flush();
        }
        replaceFile(temporary, target);
    }

    private void writeStream(File target, InputStream input) throws IOException {
        ensureParent(target);
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            output.flush();
        }
        replaceFile(temporary, target);
    }

    private void ensureParent(File target) throws IOException {
        File parent = target.getParentFile();
        if (parent == null || (parent.isDirectory() || parent.mkdirs())) return;
        throw new IOException("无法创建目录：" + parent);
    }

    private void replaceFile(File temporary, File target) throws IOException {
        if (target.exists() && !target.delete()) {
            throw new IOException("无法替换文件：" + target);
        }
        if (!temporary.renameTo(target)) {
            throw new IOException("无法写入文件：" + target);
        }
    }

    private void notifyChanged() {
        if (onChanged != null) onChanged.run();
    }

    public static final class Snapshot {
        public final boolean installed;
        public final boolean running;
        public final boolean authorized;
        public final int uid;
        public final String version;
        public final boolean rishReady;
        public final String statusLabel;

        private Snapshot(boolean installed, boolean running, boolean authorized, int uid,
                         String version, boolean rishReady, String statusLabel) {
            this.installed = installed;
            this.running = running;
            this.authorized = authorized;
            this.uid = uid;
            this.version = version;
            this.rishReady = rishReady;
            this.statusLabel = statusLabel;
        }
    }
}
