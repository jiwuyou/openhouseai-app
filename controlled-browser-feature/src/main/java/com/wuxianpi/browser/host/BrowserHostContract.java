package com.wuxianpi.browser.host;

import androidx.annotation.Nullable;

import com.termux.app.browser.ControlledBrowserContract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Transport-neutral Browser Host v1 method names and legacy command mapping. */
public final class BrowserHostContract {
    public static final int PROTOCOL_VERSION = 1;
    public static final String PROTOCOL_NAME = "wuxianpi-browser-host-v1";

    public static final String HOST_DESCRIBE = "host.describe";
    public static final String HOST_STATUS = "host.status";
    public static final String HOST_CAPABILITIES = "host.capabilities";
    public static final String TABS_LIST = "tabs.list";
    public static final String TABS_OPEN = "tabs.open";
    public static final String TABS_ACTIVATE = "tabs.activate";
    public static final String TABS_CLOSE = "tabs.close";
    public static final String PAGE_NAVIGATE = "page.navigate";
    public static final String PAGE_RELOAD = "page.reload";
    public static final String PAGE_BACK = "page.back";
    public static final String PAGE_FORWARD = "page.forward";
    public static final String PAGE_EVALUATE = "page.evaluate";
    public static final String PAGE_GET_TEXT = "page.getText";
    public static final String PAGE_GET_HTML = "page.getHtml";
    public static final String PAGE_CLICK = "page.click";
    public static final String PAGE_FILL = "page.fill";
    public static final String PAGE_WAIT = "page.wait";
    public static final String PAGE_TAP = "page.tap";
    public static final String PAGE_TYPE = "page.type";
    public static final String PAGE_SCROLL = "page.scroll";
    public static final String PAGE_SCREENSHOT = "page.screenshot";
    public static final String PAGE_RUN = "page.run";
    public static final String CDP_INVOKE = "cdp.invoke";
    public static final String APP_CONTEXT = "app.context";
    public static final String APP_DESCRIBE = "app.describe";
    public static final String APP_LIST_ACTIONS = "app.listActions";
    public static final String APP_INVOKE = "app.invoke";

    private static final Map<String, String> METHOD_TO_LEGACY;
    private static final Map<String, String> LEGACY_TO_METHOD;

    static {
        LinkedHashMap<String, String> methods = new LinkedHashMap<>();
        methods.put(HOST_STATUS, ControlledBrowserContract.COMMAND_STATUS);
        methods.put(TABS_LIST, ControlledBrowserContract.COMMAND_TABS);
        methods.put(TABS_OPEN, ControlledBrowserContract.COMMAND_NEW_TAB);
        methods.put(TABS_ACTIVATE, ControlledBrowserContract.COMMAND_SWITCH);
        methods.put(TABS_CLOSE, ControlledBrowserContract.COMMAND_CLOSE);
        methods.put(PAGE_NAVIGATE, ControlledBrowserContract.COMMAND_OPEN);
        methods.put(PAGE_RELOAD, ControlledBrowserContract.COMMAND_RELOAD);
        methods.put(PAGE_BACK, ControlledBrowserContract.COMMAND_BACK);
        methods.put(PAGE_FORWARD, ControlledBrowserContract.COMMAND_FORWARD);
        methods.put(PAGE_EVALUATE, ControlledBrowserContract.COMMAND_EVAL);
        methods.put(PAGE_GET_TEXT, ControlledBrowserContract.COMMAND_TEXT);
        methods.put(PAGE_GET_HTML, ControlledBrowserContract.COMMAND_HTML);
        methods.put(PAGE_CLICK, ControlledBrowserContract.COMMAND_CLICK);
        methods.put(PAGE_FILL, ControlledBrowserContract.COMMAND_FILL);
        methods.put(PAGE_WAIT, ControlledBrowserContract.COMMAND_WAIT);
        methods.put(PAGE_TAP, ControlledBrowserContract.COMMAND_TAP);
        methods.put(PAGE_TYPE, ControlledBrowserContract.COMMAND_TYPE);
        methods.put(PAGE_SCROLL, ControlledBrowserContract.COMMAND_SCROLL);
        methods.put(PAGE_SCREENSHOT, ControlledBrowserContract.COMMAND_SCREENSHOT);
        methods.put(PAGE_RUN, ControlledBrowserContract.COMMAND_RUN);
        methods.put(CDP_INVOKE, ControlledBrowserContract.COMMAND_CDP);
        methods.put(APP_CONTEXT, ControlledBrowserContract.COMMAND_APP_CONTEXT);
        methods.put(APP_DESCRIBE, ControlledBrowserContract.COMMAND_APP_DESCRIBE);
        methods.put(APP_LIST_ACTIONS, ControlledBrowserContract.COMMAND_APP_LIST_ACTIONS);
        methods.put(APP_INVOKE, ControlledBrowserContract.COMMAND_APP_INVOKE);
        METHOD_TO_LEGACY = Collections.unmodifiableMap(methods);

        LinkedHashMap<String, String> legacy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : methods.entrySet()) {
            legacy.put(entry.getValue(), entry.getKey());
        }
        // Historical "open" means navigate the active tab, while "new-tab" creates one.
        LEGACY_TO_METHOD = Collections.unmodifiableMap(legacy);
    }

    private BrowserHostContract() {}

    @Nullable
    public static String legacyCommandForMethod(@Nullable String method) {
        return method == null ? null : METHOD_TO_LEGACY.get(method.trim());
    }

    @Nullable
    public static String methodForLegacyCommand(@Nullable String command) {
        return command == null ? null : LEGACY_TO_METHOD.get(command.trim().toLowerCase());
    }

    public static boolean isHostMethod(@Nullable String method) {
        return HOST_DESCRIBE.equals(method) || HOST_CAPABILITIES.equals(method);
    }
}
