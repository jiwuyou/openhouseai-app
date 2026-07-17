package com.termux.app.openhouse.desktop;

import android.content.Context;
import android.content.Intent;

import com.termux.app.TermuxActivity;
import com.termux.app.activities.OpenHouseServiceControlActivity;
import com.termux.app.openhouse.OpenHouseIntents;
import com.termux.app.openhouse.components.OpenHouseComponent;
import com.termux.app.openhouse.runtime.OpenHouseEndpointSnapshot;
import com.termux.app.openhouse.servicecontrol.ServiceManagerActionResult;
import com.termux.app.openhouse.servicecontrol.ServiceManagerClient;
import com.termux.app.openhouse.servicecontrol.ServiceManagerControlClient;
import com.termux.app.openhouse.servicecontrol.ServiceManagerLogEntry;
import com.termux.app.openhouse.servicecontrol.ServiceManagerResult;
import com.termux.app.openhouse.servicecontrol.ServiceManagerServiceStatus;
import com.termux.app.openhouse.servicecontrol.ServiceManagerServiceResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DesktopAppLauncher {

    private static final int STATUS_LOG_LIMIT = 12;
    private static final String MAIN_SYSTEM_CLI = "openhouse-system";

    private final Context appContext;
    private final ServiceManagerControlClient controlClient;
    private final PublishedEndpointResolver endpointResolver;

    public DesktopAppLauncher(Context context) {
        this(context, null);
    }

    DesktopAppLauncher(Context context, PublishedEndpointResolver endpointResolver) {
        this.appContext = context == null ? null : context.getApplicationContext();
        this.controlClient = new ServiceManagerControlClient(context);
        if (endpointResolver == null) {
            OpenHouseEndpointSnapshot snapshot = new OpenHouseEndpointSnapshot();
            this.endpointResolver = (serviceId, endpointName) -> {
                OpenHouseEndpointSnapshot.Resolution endpoint = snapshot.resolve(serviceId, endpointName);
                return endpoint != null && endpoint.ready ? endpoint.url : "";
            };
        } else {
            this.endpointResolver = endpointResolver;
        }
    }

    public DesktopAppLaunchIntent buildOpenIntent(OpenHouseComponent component) {
        return buildOpenIntent(DesktopAppDescriptor.fromComponent(component));
    }

    public DesktopAppLaunchIntent buildOpenIntent(DesktopAppDescriptor app) {
        if (app == null) {
            return unsupported(null, "应用不存在。");
        }
        DesktopAppEntry entry = app.entry == null ? DesktopAppEntry.unknown("") : app.entry;
        switch (entry.type) {
            case WEBVIEW:
                String webUrl = resolveWebEntryUrl(app, entry);
                if (webUrl.isEmpty()) {
                    return statusPanel(app, isSmallPhoneApp(app)
                        ? "SmallPhone 动态 web endpoint 尚不可用，请先检查 service-manager 和 SmallPhone 服务。"
                        : "网页入口没有配置 URL。");
                }
                return DesktopAppLaunchIntent.builder(DesktopAppLaunchIntent.Kind.WEBVIEW)
                    .app(app)
                    .launchable(true)
                    .url(webUrl)
                    .message("打开网页应用。")
                    .build();
            case NATIVE_PAGE:
                if (entry.nativePage.isEmpty()) {
                    return statusPanel(app, "原生页面入口没有配置 page。");
                }
                return DesktopAppLaunchIntent.builder(DesktopAppLaunchIntent.Kind.NATIVE_PAGE)
                    .app(app)
                    .launchable(true)
                    .nativePage(entry.nativePage)
                    .message("打开原生页面。")
                    .build();
            case TERMINAL:
                return DesktopAppLaunchIntent.builder(DesktopAppLaunchIntent.Kind.TERMINAL)
                    .app(app)
                    .launchable(true)
                    .intent(buildTerminalIntent())
                    .message("打开终端。")
                    .build();
            case SERVICE_CONTROL:
                return DesktopAppLaunchIntent.builder(DesktopAppLaunchIntent.Kind.SERVICE_CONTROL)
                    .app(app)
                    .launchable(true)
                    .intent(buildServiceControlIntent(app, false))
                    .message("打开服务控制。")
                    .build();
            case ANDROID_ACTIVITY:
                if (entry.className.isEmpty()) {
                    return statusPanel(app, "Android Activity 入口没有配置 className。");
                }
                return DesktopAppLaunchIntent.builder(DesktopAppLaunchIntent.Kind.ANDROID_ACTIVITY)
                    .app(app)
                    .launchable(true)
                    .className(entry.className)
                    .intent(buildActivityIntent(entry.className))
                    .message("打开 Android Activity。")
                    .build();
            case UNKNOWN:
            default:
                if (app.hasControlEntry()) {
                    return statusPanel(app, "暂不支持这个入口类型：" + entry.rawType);
                }
                return unsupported(app, "暂不支持这个入口类型：" + entry.rawType);
        }
    }

    public Intent buildServiceControlIntent(DesktopAppDescriptor app, boolean allMode) {
        Intent intent = new Intent(appContext, OpenHouseServiceControlActivity.class);
        if (allMode) {
            intent.putExtra(OpenHouseServiceControlActivity.EXTRA_SERVICE_CONTROL_MODE,
                OpenHouseServiceControlActivity.MODE_ALL);
            return intent;
        }
        if (app == null) {
            return intent;
        }
        intent.putExtra(OpenHouseServiceControlActivity.EXTRA_SERVICE_CONTROL_COMPONENT_ID, app.id);
        intent.putExtra(OpenHouseServiceControlActivity.EXTRA_SERVICE_CONTROL_TITLE, app.displayTitle());
        intent.putExtra(OpenHouseServiceControlActivity.EXTRA_SERVICE_CONTROL_URL, app.entry == null ? "" : app.entry.url);
        intent.putExtra(OpenHouseServiceControlActivity.EXTRA_SERVICE_CONTROL_SERVICE_NAMES,
            DesktopAppServices.join(app.serviceNames));
        intent.putExtra(OpenHouseServiceControlActivity.EXTRA_SERVICE_CONTROL_SERVICE_REFS,
            DesktopAppServices.join(app.serviceRefs));
        return intent;
    }

    public DesktopAppStatus loadStatus(OpenHouseComponent component) {
        return loadStatus(DesktopAppDescriptor.fromComponent(component));
    }

    public DesktopAppStatus loadStatus(DesktopAppDescriptor app) {
        if (app == null) {
            return DesktopAppStatus.builder()
                .state(DesktopAppStatus.State.UNKNOWN)
                .headline("状态未知")
                .detail("应用不存在。")
                .build();
        }
        List<String> serviceIds = app.serviceIds();
        if (serviceIds.isEmpty()) {
            DesktopAppStatus.State state = app.hasEntry() ? DesktopAppStatus.State.READY : DesktopAppStatus.State.UNKNOWN;
            return DesktopAppStatus.builder()
                .app(app)
                .state(state)
                .headline(state == DesktopAppStatus.State.READY ? "可打开" : "没有运行状态")
                .detail((app.hasEntry() ? "这个应用没有注册 service-manager 服务。" : "这个应用没有可打开入口或服务。")
                    + "\n" + mainSystemCheckHint(app, "service-manager 没有关联服务可以排查。", Collections.emptyList()))
                .serviceManagerReachable(false)
                .services(Collections.emptyList())
                .build();
        }

        ServiceManagerResult health = safeHealthCheck();
        boolean serviceManagerReachable = health != null && health.success;
        List<DesktopAppServiceStatus> services = new ArrayList<>();
        if (!serviceManagerReachable) {
            for (String serviceId : serviceIds) {
                services.add(new DesktopAppServiceStatus(false, serviceId, "unknown", "", -1, "",
                    health == null ? "service-manager 暂不可达。" : health.message, health == null ? 0 : health.code));
            }
            return DesktopAppStatus.builder()
                .app(app)
                .state(DesktopAppStatus.State.UNREACHABLE)
                .headline("控制中枢不可达")
                .detail(firstNonBlank(health == null ? "" : health.message, "service-manager 暂不可达，无法读取应用状态。"))
                .serviceManagerReachable(false)
                .services(services)
                .build();
        }

        boolean anyRunning = false;
        boolean anyStarting = false;
        boolean anyStopped = false;
        boolean anyFailure = false;
        List<String> missingServiceIds = Collections.emptyList();
        ServiceManagerServiceResolver.Resolution resolution = safeResolveServiceIds(app, serviceIds);
        if (resolution != null) {
            serviceIds = resolution.serviceIds;
            missingServiceIds = resolution.missingServiceIds;
        }
        if (serviceIds.isEmpty()) {
            return DesktopAppStatus.builder()
                .app(app)
                .serviceIds(Collections.emptyList())
                .state(DesktopAppStatus.State.FAILED)
                .headline("服务未注册")
                .detail("这个应用注册的服务当前未出现在 service-manager 列表中。"
                    + formatMissingServices(missingServiceIds))
                .serviceManagerReachable(true)
                .services(Collections.emptyList())
                .build();
        }
        for (String serviceId : serviceIds) {
            DesktopAppServiceStatus service = safeLoadServiceStatus(serviceId);
            services.add(service);
            anyRunning = anyRunning || service.isRunning();
            anyStarting = anyStarting || service.isStarting();
            anyStopped = anyStopped || service.isStopped();
            anyFailure = anyFailure || !service.success;
        }

        DesktopAppStatus.State state;
        String headline;
        if (anyFailure) {
            state = DesktopAppStatus.State.FAILED;
            headline = "部分服务状态异常";
        } else if (anyStarting) {
            state = DesktopAppStatus.State.STARTING;
            headline = "正在启动";
        } else if (anyStopped) {
            state = DesktopAppStatus.State.STOPPED;
            headline = "部分服务未运行";
        } else if (anyRunning) {
            state = DesktopAppStatus.State.RUNNING;
            headline = "运行中";
        } else {
            state = DesktopAppStatus.State.UNKNOWN;
            headline = "状态未知";
        }

        String detail = buildStatusDetail(app, services) + formatIgnoredServices(missingServiceIds);
        return DesktopAppStatus.builder()
            .app(app)
            .serviceIds(serviceIds)
            .state(state)
            .headline(headline)
            .detail(detail)
            .serviceManagerReachable(true)
            .services(services)
            .build();
    }

    public DesktopAppStatusSheetModel buildStatusSheetModel(OpenHouseComponent component) {
        return buildStatusSheetModel(DesktopAppDescriptor.fromComponent(component));
    }

    public DesktopAppStatusSheetModel buildStatusSheetModel(DesktopAppDescriptor app) {
        DesktopAppStatus status = loadStatus(app);
        return new DesktopAppStatusSheetModel(
            app,
            status,
            suggestActions(app, status),
            buildDetailLines(app, status),
            safeLoadRecentLogs(firstServiceId(status))
        );
    }

    public List<DesktopAppAction> suggestActions(DesktopAppDescriptor app, DesktopAppStatus status) {
        List<DesktopAppAction> actions = new ArrayList<>();
        boolean hasEntry = app != null && app.hasEntry();
        boolean hasServices = status != null && !status.serviceIds.isEmpty();
        boolean serviceLayerClean = serviceManagerFoundNoServiceProblem(status == null ? null : status.services);
        String firstServiceId = firstServiceId(status);

        actions.add(DesktopAppAction.open(hasEntry, hasEntry ? "" : "没有可打开入口。"));
        actions.add(DesktopAppAction.start(firstServiceId, hasServices, hasServices ? "" : "没有可启动服务。"));
        actions.add(DesktopAppAction.stop(firstServiceId, hasServices, hasServices ? "" : "没有可停止服务。"));
        actions.add(DesktopAppAction.restart(firstServiceId, hasServices, hasServices ? "" : "没有可重启服务。"));
        actions.add(DesktopAppAction.log(firstServiceId, hasServices, hasServices ? "" : "没有服务日志。"));
        actions.add(DesktopAppAction.serviceControl(hasServices, hasServices ? "" : "没有 service-manager 服务。"));
        actions.add(DesktopAppAction.repair(hasServices && !serviceLayerClean,
            serviceLayerClean ? "service-manager 未发现服务层问题，请检查主系统。" : (hasServices ? "" : "没有可修复服务。")));
        return Collections.unmodifiableList(actions);
    }

    public DesktopAppActionResult performAction(DesktopAppAction action) {
        if (action == null || !action.enabled) {
            return DesktopAppActionResult.unhandled(action, action == null ? "动作不存在。" : firstNonBlank(action.reason, "动作不可用。"));
        }
        String serviceAction = action.serviceManagerAction();
        if (serviceAction.isEmpty() || action.serviceId.isEmpty()) {
            return DesktopAppActionResult.unhandled(action, "这个动作需要由 Activity 处理。");
        }
        try {
            ServiceManagerActionResult result = controlClient.runAction(action.serviceId, serviceAction);
            if (result.success()) {
                return DesktopAppActionResult.success(action, result.message(), result.state(), result.pid(), result.code());
            }
            return DesktopAppActionResult.failure(action, result.message(), result.code());
        } catch (Exception e) {
            return DesktopAppActionResult.failure(action, safeErrorMessage(e), 0);
        }
    }

    private Intent buildTerminalIntent() {
        Intent intent = new Intent(appContext, TermuxActivity.class);
        intent.putExtra(OpenHouseIntents.EXTRA_OPENHOUSE_ENTRY_SOURCE, OpenHouseIntents.ENTRY_SOURCE_HOME);
        return intent;
    }

    private Intent buildActivityIntent(String className) {
        Intent intent = new Intent();
        intent.setClassName(appContext, className);
        return intent;
    }

    private DesktopAppLaunchIntent statusPanel(DesktopAppDescriptor app, String message) {
        return DesktopAppLaunchIntent.builder(DesktopAppLaunchIntent.Kind.STATUS_PANEL)
            .app(app)
            .launchable(false)
            .message(message)
            .build();
    }

    private DesktopAppLaunchIntent unsupported(DesktopAppDescriptor app, String message) {
        return DesktopAppLaunchIntent.builder(DesktopAppLaunchIntent.Kind.UNSUPPORTED)
            .app(app)
            .launchable(false)
            .message(message)
            .build();
    }

    private String resolveWebEntryUrl(DesktopAppDescriptor app, DesktopAppEntry entry) {
        if (isSmallPhoneApp(app)) {
            try {
                return firstNonBlank(endpointResolver.resolve(
                    "smallphone-frontend-beta",
                    "web"
                ), "");
            } catch (Exception ignored) {
                // Opening is gated on the published endpoint. Never guess a port.
            }
            return "";
        }
        return entry == null ? "" : entry.url;
    }

    private boolean isSmallPhoneApp(DesktopAppDescriptor app) {
        if (app == null) {
            return false;
        }
        if ("messages".equalsIgnoreCase(app.id) || "smallphone".equalsIgnoreCase(app.id)) {
            return true;
        }
        for (String serviceId : app.serviceIds()) {
            if ("smallphone-frontend-beta".equalsIgnoreCase(serviceId)
                || "smallphone-core".equalsIgnoreCase(serviceId)) {
                return true;
            }
        }
        return false;
    }

    interface PublishedEndpointResolver {
        String resolve(String serviceId, String endpointName);
    }

    private ServiceManagerResult safeHealthCheck() {
        try {
            return controlClient.healthCheck();
        } catch (Exception e) {
            return null;
        }
    }

    private DesktopAppServiceStatus safeLoadServiceStatus(String serviceId) {
        try {
            ServiceManagerServiceStatus status = controlClient.getStatus(serviceId);
            return new DesktopAppServiceStatus(
                status.success(),
                status.serviceId(),
                status.state(),
                status.provider(),
                status.pid(),
                status.url(),
                status.message(),
                status.code()
            );
        } catch (Exception e) {
            return new DesktopAppServiceStatus(false, serviceId, "unknown", "", -1, "", safeErrorMessage(e), 0);
        }
    }

    private ServiceManagerServiceResolver.Resolution safeResolveServiceIds(
        DesktopAppDescriptor app,
        List<String> requestedServiceIds
    ) {
        try {
            return ServiceManagerServiceResolver.resolve(
                app == null ? "" : app.id,
                requestedServiceIds,
                controlClient.listServices());
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> safeLoadRecentLogs(String serviceId) {
        if (serviceId == null || serviceId.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            List<ServiceManagerLogEntry> entries = controlClient.getLogs(serviceId, STATUS_LOG_LIMIT);
            List<String> lines = new ArrayList<>();
            for (ServiceManagerLogEntry entry : entries) {
                if (entry == null) {
                    continue;
                }
                String line = firstNonBlank(entry.message(), entry.raw());
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
            return lines.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(lines);
        } catch (Exception e) {
            List<String> lines = new ArrayList<>();
            lines.add("日志读取失败：" + safeErrorMessage(e));
            return Collections.unmodifiableList(lines);
        }
    }

    private List<String> buildDetailLines(DesktopAppDescriptor app, DesktopAppStatus status) {
        List<String> lines = new ArrayList<>();
        if (app != null) {
            lines.add("appId: " + app.id);
            lines.add("entry: " + (app.entry == null ? "unknown" : app.entry.rawType));
            if (app.entry != null && !app.entry.url.isEmpty()) {
                lines.add("url: " + app.entry.url);
            }
            if (app.entry != null && !app.entry.nativePage.isEmpty()) {
                lines.add("page: " + app.entry.nativePage);
            }
            if (app.entry != null && !app.entry.className.isEmpty()) {
                lines.add("activity: " + app.entry.className);
            }
        }
        if (status != null) {
            lines.add("service-manager: " + (status.serviceManagerReachable ? "reachable" : "unreachable"));
            for (DesktopAppServiceStatus service : status.services) {
                lines.add(service.displayLine());
            }
        }
        return lines.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(lines);
    }

    private String buildStatusDetail(DesktopAppDescriptor app, List<DesktopAppServiceStatus> services) {
        if (services == null || services.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (DesktopAppServiceStatus service : services) {
            if (service == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(service.displayLine());
        }
        if (serviceManagerFoundNoServiceProblem(services)) {
            appendLine(builder, mainSystemCheckHint(app, "服务层未发现问题。", services));
        }
        return builder.toString();
    }

    private boolean serviceManagerFoundNoServiceProblem(List<DesktopAppServiceStatus> services) {
        if (services == null || services.isEmpty()) {
            return false;
        }
        for (DesktopAppServiceStatus service : services) {
            if (service == null || !service.success || !service.isRunning()) {
                return false;
            }
        }
        return true;
    }

    private String mainSystemCheckHint(
        DesktopAppDescriptor app,
        String prefix,
        List<DesktopAppServiceStatus> services
    ) {
        String cleanPrefix = firstNonBlank(prefix, "service-manager 未发现服务层问题。");
        return cleanPrefix
            + "如仍有问题，请把这句话发给 AI：请检查 " + subjectId(app)
            + " 的主系统信息，命令：" + mainSystemCommand("check", app)
            + "。" + serviceContext(services);
    }

    private String mainSystemCommand(String command, DesktopAppDescriptor app) {
        return MAIN_SYSTEM_CLI + " " + firstNonBlank(command, "check") + " " + shellArg(subjectId(app));
    }

    private String serviceContext(List<DesktopAppServiceStatus> services) {
        if (services == null || services.isEmpty()) {
            return "服务上下文：未关联 service-manager 服务。";
        }
        StringBuilder builder = new StringBuilder("服务上下文：");
        int count = 0;
        for (DesktopAppServiceStatus service : services) {
            if (service == null || service.serviceId.isEmpty()) {
                continue;
            }
            if (count > 0) {
                builder.append("；");
            }
            builder.append(service.serviceId)
                .append("(state=")
                .append(firstNonBlank(service.state, "unknown"))
                .append(", manager=service-manager, provider=")
                .append(firstNonBlank(service.provider, "unknown"))
                .append(")");
            count++;
        }
        if (count == 0) {
            return "服务上下文：未关联 service-manager 服务。";
        }
        return builder
            .append("。运行时归属以 ")
            .append(MAIN_SYSTEM_CLI)
            .append(" describe 输出的主体声明为准。")
            .toString();
    }

    private String subjectId(DesktopAppDescriptor app) {
        return firstNonBlank(app == null ? "" : app.id, "subject-id");
    }

    private String shellArg(String value) {
        String text = firstNonBlank(value, "subject-id");
        boolean safe = true;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (!((current >= 'a' && current <= 'z')
                || (current >= 'A' && current <= 'Z')
                || (current >= '0' && current <= '9')
                || current == '_'
                || current == '-'
                || current == '.')) {
                safe = false;
                break;
            }
        }
        if (safe) {
            return text;
        }
        return "'" + text.replace("'", "'\"'\"'") + "'";
    }

    private void appendLine(StringBuilder builder, String line) {
        if (builder == null || line == null || line.trim().isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(line.trim());
    }

    private String formatIgnoredServices(List<String> missingServiceIds) {
        if (missingServiceIds == null || missingServiceIds.isEmpty()) {
            return "";
        }
        return "\n已忽略当前未注册服务：" + DesktopAppServices.join(missingServiceIds);
    }

    private String formatMissingServices(List<String> missingServiceIds) {
        if (missingServiceIds == null || missingServiceIds.isEmpty()) {
            return "";
        }
        return "\n未找到：" + DesktopAppServices.join(missingServiceIds);
    }

    private String firstServiceId(DesktopAppStatus status) {
        if (status == null || status.serviceIds.isEmpty()) {
            return "";
        }
        return status.serviceIds.get(0);
    }

    private String safeErrorMessage(Exception e) {
        if (e == null || e.getMessage() == null || e.getMessage().trim().isEmpty()) {
            return "未知错误";
        }
        return e.getMessage().trim();
    }

    private String firstNonBlank(String first, String fallback) {
        String text = first == null ? "" : first.trim();
        return text.isEmpty() ? (fallback == null ? "" : fallback.trim()) : text;
    }
}
