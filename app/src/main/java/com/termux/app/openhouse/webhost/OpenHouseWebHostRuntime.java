package com.termux.app.openhouse.webhost;

import android.content.Context;
import android.net.Uri;

import com.termux.app.openhouse.OpenHouseMaintainerRunner;
import com.termux.app.openhouse.servicecontrol.ServiceManagerClient;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class OpenHouseWebHostRuntime {

    public static final String OPENHOUSE_WEB_ORIGIN = "http://127.0.0.1:22110";
    public static final String OPENHOUSE_WEB_HEALTH = OPENHOUSE_WEB_ORIGIN + "/health";
    public static final String SERVICE_MANAGER_ORIGIN = "http://127.0.0.1:20087";
    public static final String SERVICE_MANAGER_HEALTH = SERVICE_MANAGER_ORIGIN + "/api/v1/health";
    public static final String TICKET_RELATIVE_PATH = ".local/share/openhouseai/openhouse-web/bootstrap-ticket.json";

    private static final String LOG_TAG = "OpenHouseWebRuntime";
    private static final int MAX_TICKET_BYTES = 16 * 1024;

    private final Context context;
    public OpenHouseWebHostRuntime(Context context) {
        this.context = context.getApplicationContext();
    }

    public Result prepare(boolean allowRepair) {
        if (!waitForHealth(SERVICE_MANAGER_HEALTH, 5, 400)) {
            return nativeRecovery("Termux native service-manager 不可达；不会自动执行修复、重启或 Termux 命令。");
        }

        if (waitForHealth(OPENHOUSE_WEB_HEALTH, 12, 500)) {
            String ticket = readLatestTicket();
            if (!ticket.isEmpty()) {
                return Result.openHouseWeb(OPENHOUSE_WEB_ORIGIN + "/#ticket=" + Uri.encode(ticket));
            }
            return Result.openHouseWeb(OPENHOUSE_WEB_ORIGIN + "/");
        }

        return serviceManagerFallback();
    }

    public Result serviceManagerFallback() {
        if (!waitForHealth(SERVICE_MANAGER_HEALTH, 2, 250)) {
            return nativeRecovery("service-manager 回退页也不可访问。");
        }
        try {
            String path = new ServiceManagerClient(SERVICE_MANAGER_ORIGIN).issueWebSessionPath();
            if (!isSafeServiceManagerSessionPath(path)) {
                return nativeRecovery("service-manager 返回了无效的一次性会话入口。");
            }
            return Result.serviceManager(SERVICE_MANAGER_ORIGIN + path);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to request service-manager Web session", e);
            return nativeRecovery("无法取得 service-manager 一次性 Web 会话；健康的旧版 service-manager 不会被自动升级。");
        }
    }

    private Result nativeRecovery(String detail) {
        return Result.nativeRecovery(detail
            + "\n\n更新资源目录：" + OpenHouseMaintainerRunner.UPDATE_RESOURCE_ROOT
            + "\n可复制给 AI：" + OpenHouseMaintainerRunner.COPYABLE_AI_GUIDE);
    }

    public static boolean shouldOpenNativeRecovery(boolean serviceManagerHealthy) {
        return !serviceManagerHealthy;
    }

    public static AutomaticRecoveryAction automaticRecoveryAction() {
        return AutomaticRecoveryAction.NATIVE_RECOVERY_ONLY;
    }

    public String readLatestTicket() {
        File file = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, TICKET_RELATIVE_PATH);
        if (!file.isFile() || !file.canRead() || file.length() <= 0 || file.length() > MAX_TICKET_BYTES) return "";
        try {
            byte[] raw = readFile(file, MAX_TICKET_BYTES);
            JSONObject object = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            String ticket = object.optString("ticket", "").trim();
            return isSafeOneTimeTicket(ticket) ? ticket : "";
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Could not read OpenHouse Web handoff: " + e.getMessage());
            return "";
        }
    }

    public static boolean isAllowedLoopbackUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try {
            URI uri = new URI(value.trim());
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null) return false;
            String host = uri.getHost();
            if (host == null) return false;
            String normalized = host.toLowerCase(Locale.US);
            return "127.0.0.1".equals(normalized)
                || "localhost".equals(normalized)
                || "::1".equals(normalized)
                || "[::1]".equals(normalized);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Final policy gate used immediately before the host WebView loads a prepared result. */
    public static boolean isSafeWebViewTarget(Target target, String url) {
        return target != null
            && target != Target.NATIVE_RECOVERY
            && isAllowedLoopbackUrl(url);
    }

    /** Popup/multi-window navigation is intentionally unavailable in the thin WebHost. */
    public static boolean arePopupWindowsAllowed() {
        return false;
    }

    public static boolean isSafeOneTimeTicket(String value) {
        if (value == null || value.length() < 32 || value.length() > 160) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_')) return false;
        }
        return true;
    }

    public static boolean isSafeServiceManagerSessionPath(String path) {
        if (path == null || !path.startsWith("/web-session?ticket=")) return false;
        String ticket = path.substring("/web-session?ticket=".length());
        return isSafeOneTimeTicket(ticket);
    }

    private boolean waitForHealth(String url, int attempts, long pauseMs) {
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (probe(url)) return true;
            if (attempt + 1 < attempts) {
                try { Thread.sleep(pauseMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            }
        }
        return false;
    }

    private boolean probe(String value) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(value).openConnection();
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1500);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            int code = connection.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] readFile(File file, int limit) throws Exception {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() + count > limit) throw new IllegalArgumentException("ticket file too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    public enum Target { OPENHOUSE_WEB, SERVICE_MANAGER, NATIVE_RECOVERY }

    public enum AutomaticRecoveryAction { NATIVE_RECOVERY_ONLY }

    public static final class Result {
        public final Target target;
        public final String url;
        public final String detail;

        private Result(Target target, String url, String detail) {
            this.target = target;
            this.url = url == null ? "" : url;
            this.detail = detail == null ? "" : detail;
        }

        static Result openHouseWeb(String url) { return new Result(Target.OPENHOUSE_WEB, url, "OpenHouse Web 已就绪。"); }
        static Result serviceManager(String url) { return new Result(Target.SERVICE_MANAGER, url, "OpenHouse Web 未就绪，已进入 service-manager 安全控制页。"); }
        static Result nativeRecovery(String detail) { return new Result(Target.NATIVE_RECOVERY, "", detail); }
    }
}
