package com.termux.app.smallphone;

import android.content.Context;

import com.termux.app.openhouse.OpenHouseMaintainerRunner;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

public final class SmallPhoneRuntime {

    public static final String SERVICE_MANAGER_URL = "http://127.0.0.1:20087";
    public static final String SERVICE_MANAGER_HEALTH_URL = SERVICE_MANAGER_URL + "/api/v1/health";
    public static final String SMALLPHONE_URL = "http://127.0.0.1:22082/";
    public static final String SMALLPHONE_CORE_URL = "http://127.0.0.1:22000/";
    public static final String SMALLPHONE_CORE_HEALTH_URL = SMALLPHONE_CORE_URL + "health";
    public static final String CC_CONNECT_URL = "http://127.0.0.1:21040/";
    public static final String CC_CONNECT_HEALTH_URL = CC_CONNECT_URL + "healthz";

    private static final String LOG_TAG = "SmallPhoneRuntime";
    private static final int CONNECT_TIMEOUT_MS = 1200;
    private static final int READ_TIMEOUT_MS = 1800;

    private final Context context;

    public SmallPhoneRuntime(Context context) {
        this.context = context.getApplicationContext();
    }

    public Status loadStatus() {
        Endpoint serviceManager = probe("service-manager", SERVICE_MANAGER_HEALTH_URL);
        Endpoint smallPhone = probe("SmallPhone", SMALLPHONE_URL);
        Endpoint smallPhoneCore = probe("SmallPhone core", SMALLPHONE_CORE_HEALTH_URL);
        boolean ccDisabled = isCcConnectDisabled();
        Endpoint ccConnect = ccDisabled
            ? Endpoint.disabled("cc-connect", CC_CONNECT_URL)
            : probe("cc-connect", CC_CONNECT_HEALTH_URL);

        return new Status(serviceManager, smallPhone, smallPhoneCore, ccConnect, ccDisabled);
    }

    public OpenHouseMaintainerRunner.Result startStack() {
        return new OpenHouseMaintainerRunner(context)
            .run(OpenHouseMaintainerRunner.Action.START_SMALLPHONE, 0);
    }

    public OpenHouseMaintainerRunner.Result repairStack() {
        return new OpenHouseMaintainerRunner(context)
            .run(OpenHouseMaintainerRunner.Action.REPAIR_SMALLPHONE, 0);
    }

    private Endpoint probe(String label, String... urls) {
        String lastUrl = urls == null || urls.length == 0 ? "" : urls[0];
        String lastDetail = "未检查";
        if (urls == null) {
            return Endpoint.down(label, lastUrl, lastDetail);
        }

        for (String url : urls) {
            if (url == null || url.trim().isEmpty()) {
                continue;
            }

            lastUrl = url;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setUseCaches(false);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("GET");
                int code = connection.getResponseCode();
                if (isHealthyHttpCode(code)) {
                    return Endpoint.reachable(label, url, code);
                }
                lastDetail = "HTTP " + code;
            } catch (IOException e) {
                lastDetail = e.getClass().getSimpleName();
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to probe " + label + " at " + url, e);
                lastDetail = e.getClass().getSimpleName();
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        return Endpoint.down(label, lastUrl, lastDetail);
    }

    private boolean isHealthyHttpCode(int code) {
        return code >= 200 && code < 400;
    }

    private boolean isCcConnectDisabled() {
        File stateDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".smallphoneai");
        File ubuntuStateDir = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH,
            "var/lib/proot-distro/installed-rootfs/ubuntu/root/.smallphoneai");
        return new File(stateDir, "cc-connect.disabled").isFile()
            || new File(stateDir, "disable-cc-connect").isFile()
            || new File(ubuntuStateDir, "cc-connect.disabled").isFile()
            || new File(ubuntuStateDir, "disable-cc-connect").isFile()
            || isTruthy(System.getenv("SMALLPHONEAI_CC_CONNECT_DISABLED"))
            || isTruthy(System.getenv("SMALLPHONEAI_DISABLE_CC_CONNECT"));
    }

    private boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim();
        return "1".equals(normalized)
            || "true".equalsIgnoreCase(normalized)
            || "yes".equalsIgnoreCase(normalized)
            || "on".equalsIgnoreCase(normalized);
    }

    public static final class Status {
        public final Endpoint serviceManager;
        public final Endpoint smallPhone;
        public final Endpoint smallPhoneCore;
        public final Endpoint ccConnect;
        public final boolean ccConnectDisabled;

        Status(Endpoint serviceManager,
               Endpoint smallPhone,
               Endpoint smallPhoneCore,
               Endpoint ccConnect,
               boolean ccConnectDisabled) {
            this.serviceManager = serviceManager;
            this.smallPhone = smallPhone;
            this.smallPhoneCore = smallPhoneCore;
            this.ccConnect = ccConnect;
            this.ccConnectDisabled = ccConnectDisabled;
        }

        public boolean isHealthy() {
            return serviceManager.reachable
                && smallPhone.reachable
                && smallPhoneCore.reachable
                && (ccConnect.reachable || ccConnectDisabled);
        }

        public String headline() {
            if (isHealthy()) {
                return "SmallPhone 运行栈已就绪";
            }
            if (!serviceManager.reachable) {
                return "service-manager 未就绪";
            }
            if (!smallPhone.reachable) {
                return "SmallPhone 入口未就绪";
            }
            if (!smallPhoneCore.reachable) {
                return "SmallPhone Core API 未就绪";
            }
            return "cc-connect 未就绪";
        }

        public String detail() {
            if (isHealthy()) {
                return "已通过 service-manager、SmallPhone 和 cc-connect 健康门禁。";
            }
            if (!serviceManager.reachable) {
                return "请启动或修复 SmallPhoneAI 运行栈；service-manager 是核心服务控制面。";
            }
            if (!smallPhone.reachable) {
                return "service-manager 可访问，但 SmallPhone 默认入口还没有响应。";
            }
            if (!smallPhoneCore.reachable) {
                return "SmallPhone 页面可访问，但核心 API 还没有响应。";
            }
            return "SmallPhone 可访问，但 cc-connect 尚未响应；如确需禁用，可在 ~/.smallphoneai/cc-connect.disabled 放置禁用标记。";
        }
    }

    public static final class Endpoint {
        public final String label;
        public final String url;
        public final boolean reachable;
        public final boolean disabled;
        public final int httpCode;
        public final String detail;

        private Endpoint(String label, String url, boolean reachable, boolean disabled, int httpCode, String detail) {
            this.label = label;
            this.url = url == null ? "" : url;
            this.reachable = reachable;
            this.disabled = disabled;
            this.httpCode = httpCode;
            this.detail = detail == null ? "" : detail;
        }

        static Endpoint reachable(String label, String url, int httpCode) {
            return new Endpoint(label, url, true, false, httpCode, "HTTP " + httpCode);
        }

        static Endpoint down(String label, String url, String detail) {
            return new Endpoint(label, url, false, false, -1, detail);
        }

        static Endpoint disabled(String label, String url) {
            return new Endpoint(label, url, true, true, 0, "已按配置禁用");
        }

        public String display() {
            if (disabled) {
                return label + "：已禁用";
            }
            if (reachable) {
                return label + "：可访问 · " + url;
            }
            return label + "：不可访问 · " + detail;
        }
    }
}
