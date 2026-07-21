package com.wuxianpi.openhouse.core.registry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class OpenHouseBuiltins {
    public static final String DESKTOP_ID = "desktop";
    public static final String BASIC_ID = "basic";
    public static final String ADVANCED_ID = "advanced";
    public static final String REPAIR_ID = "repair";
    public static final String SERVICE_CONTROL_ID = "service-control";
    public static final String SETUP_ID = "setup";
    public static final String SETTINGS_ID = "settings";

    private static final Set<String> PROTECTED_IDS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        DESKTOP_ID, BASIC_ID, ADVANCED_ID, REPAIR_ID, SERVICE_CONTROL_ID, SETUP_ID, SETTINGS_ID
    )));

    private OpenHouseBuiltins() {}

    public static Set<String> protectedIds() { return PROTECTED_IDS; }
    public static boolean isProtectedId(String id) { return id != null && PROTECTED_IDS.contains(id.trim()); }

    public static List<OpenHouseComponent> components() {
        List<OpenHouseComponent> values = new ArrayList<>();
        values.add(nativeRoute(DESKTOP_ID, "桌面", "应用与工具入口", "desktop", 1, "grid"));
        values.add(nativeRoute(BASIC_ID, "基础模式", "Operit UI 与主 Node Pi Runtime", "ai", 10, "brain"));
        values.add(nativeRoute(ADVANCED_ID, "高级 UI", "完整 Web 工作台", "ai", 20, "sparkles"));
        values.add(nativeRoute(REPAIR_ID, "维修模式", "独立诊断与修复", "tools", 30, "wrench"));
        values.add(new OpenHouseComponent(SERVICE_CONTROL_ID, "服务控制", "启动、停止和重启本机服务",
            "tools", 40, "settings", "服", 40, false, true, true,
            OpenHouseComponent.EntryType.SERVICE_CONTROL, "", "", "", "控制", true,
            false, false, true, "builtin", Collections.emptyList(), Collections.emptyList()));
        values.add(nativeRoute(SETUP_ID, "安装引导", "首次配置运行环境", "tools", 50, "sparkles"));
        values.add(nativeRoute(SETTINGS_ID, "设置", "启动、桌面与兼容设置", "tools", 60, "sliders"));
        return Collections.unmodifiableList(values);
    }

    private static OpenHouseComponent nativeRoute(String id, String title, String subtitle,
                                                   String section, int order, String icon) {
        return new OpenHouseComponent(id, title, subtitle, section, order, icon,
            title.substring(0, 1), order, false, true, true,
            OpenHouseComponent.EntryType.NATIVE_PAGE, "", id, "", "", true,
            false, DESKTOP_ID.equals(id), true, "builtin", Collections.emptyList(), Collections.emptyList());
    }
}
