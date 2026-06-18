package com.termux.app.browser;

/**
 * Stable command and extra names for the OpenHouse controlled browser.
 *
 * These constants are intentionally plain strings so callers can use them from
 * `adb shell am start`/Termux command lines as well as from Java code.
 */
public final class ControlledBrowserContract {

    private ControlledBrowserContract() {}

    public static final String ACTION_CONTROLLED_BROWSER_COMMAND =
        "com.termux.app.browser.action.CONTROLLED_BROWSER_COMMAND";

    public static final String EXTRA_OPENHOUSE_PAGE =
        "com.termux.openhouse.PAGE";
    public static final String PAGE_CONTROLLED_BROWSER = "controlled_browser";

    public static final String EXTRA_COMMAND =
        "com.termux.app.browser.extra.COMMAND";
    public static final String EXTRA_URL =
        "com.termux.app.browser.extra.URL";
    public static final String EXTRA_TAB_ID =
        "com.termux.app.browser.extra.TAB_ID";
    public static final String EXTRA_TAB_INDEX =
        "com.termux.app.browser.extra.TAB_INDEX";
    public static final String EXTRA_TITLE =
        "com.termux.app.browser.extra.TITLE";
    public static final String EXTRA_ACTIVATE =
        "com.termux.app.browser.extra.ACTIVATE";
    public static final String EXTRA_REQUEST_ID =
        "com.termux.app.browser.extra.REQUEST_ID";
    public static final String EXTRA_REQUEST_FILE =
        "com.termux.app.browser.extra.REQUEST_FILE";
    public static final String EXTRA_RESULT_FILE =
        "com.termux.app.browser.extra.RESULT_FILE";
    public static final String EXTRA_TIMEOUT_MS =
        "com.termux.app.browser.extra.TIMEOUT_MS";
    public static final String EXTRA_TOKEN =
        "com.termux.app.browser.extra.TOKEN";
    public static final String EXTRA_PAYLOAD =
        "com.termux.app.browser.extra.PAYLOAD";
    public static final String EXTRA_OUTPUT =
        "com.termux.app.browser.extra.OUTPUT";
    public static final String EXTRA_METHOD =
        "com.termux.app.browser.extra.METHOD";
    public static final String EXTRA_PARAMS =
        "com.termux.app.browser.extra.PARAMS";

    public static final String LEGACY_EXTRA_COMMAND =
        "com.termux.openhouse.browser.COMMAND";
    public static final String LEGACY_EXTRA_URL =
        "com.termux.openhouse.browser.URL";
    public static final String LEGACY_EXTRA_TAB =
        "com.termux.openhouse.browser.TAB";

    public static final String COMMAND_OPEN = "open";
    public static final String COMMAND_NEW_TAB = "new-tab";
    public static final String COMMAND_SWITCH = "switch";
    public static final String COMMAND_CLOSE = "close";
    public static final String COMMAND_RELOAD = "reload";
    public static final String COMMAND_BACK = "back";
    public static final String COMMAND_FORWARD = "forward";
    public static final String COMMAND_STATUS = "status";
    public static final String COMMAND_TABS = "tabs";
    public static final String COMMAND_EVAL = "eval";
    public static final String COMMAND_TEXT = "text";
    public static final String COMMAND_HTML = "html";
    public static final String COMMAND_CLICK = "click";
    public static final String COMMAND_FILL = "fill";
    public static final String COMMAND_WAIT = "wait";
    public static final String COMMAND_TAP = "tap";
    public static final String COMMAND_TYPE = "type";
    public static final String COMMAND_SCROLL = "scroll";
    public static final String COMMAND_SCREENSHOT = "screenshot";
    public static final String COMMAND_CDP = "cdp";
    public static final String COMMAND_RUN = "run";

    public static final String FIELD_COMMAND = "command";
    public static final String FIELD_URL = "url";
    public static final String FIELD_TAB_ID = "tabId";
    public static final String FIELD_TAB_INDEX = "tabIndex";
    public static final String FIELD_TITLE = "title";
    public static final String FIELD_ACTIVATE = "activate";
    public static final String FIELD_REQUEST_ID = "requestId";
    public static final String FIELD_REQUEST_FILE = "requestFile";
    public static final String FIELD_RESULT_FILE = "resultFile";
    public static final String FIELD_TIMEOUT_MS = "timeoutMs";
    public static final String FIELD_TOKEN = "token";
    public static final String FIELD_PAYLOAD = "payload";
    public static final String FIELD_OUTPUT = "output";
    public static final String FIELD_METHOD = "method";
    public static final String FIELD_PARAMS = "params";
}
