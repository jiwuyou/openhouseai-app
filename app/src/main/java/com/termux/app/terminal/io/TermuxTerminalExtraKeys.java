package com.termux.app.terminal.io;

import android.annotation.SuppressLint;
import android.view.Gravity;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.drawerlayout.widget.DrawerLayout;

import com.termux.app.TermuxActivity;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.settings.properties.TermuxSharedProperties;
import com.termux.shared.termux.terminal.io.TerminalExtraKeys;
import com.termux.view.TerminalView;

import org.json.JSONException;

public class TermuxTerminalExtraKeys extends TerminalExtraKeys {

    private ExtraKeysInfo mExtraKeysInfo;
    private ExtraKeysInfo[] mExtraKeysInfos = new ExtraKeysInfo[0];
    private boolean mUsingOpenHouseDefaultExtraKeys;

    final TermuxActivity mActivity;
    final TermuxTerminalViewClient mTermuxTerminalViewClient;
    final TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    private static final String LOG_TAG = "TermuxTerminalExtraKeys";
    private static final String OPENHOUSE_BASE_EXTRA_KEYS_ROWS =
        "['ESC','/','-','HOME','UP',{macro: 'exit ENTER', display: 'exit'}, " +
            "{macro: 'proot-distro SPACE login SPACE ubuntu ENTER', display: 'ubuntu'}], " +
            "['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','KEYBOARD']";
    private static final String OPENHOUSE_EXTRA_KEYS_PAGE_ONE =
        "[" + OPENHOUSE_BASE_EXTRA_KEYS_ROWS + ", " +
            "[{key: 'claude ', display: 'claude'}, {key: 'reasonix ', display: 'reasonix'}, " +
            "{key: 'codex ', display: 'codex'}, {key: 'oc ', display: 'oc'}, " +
            "{key: '--continue', display: '--continue'}]]";
    private static final String OPENHOUSE_EXTRA_KEYS_PAGE_TWO =
        "[" + OPENHOUSE_BASE_EXTRA_KEYS_ROWS + ", " +
            "[{key: 'claude --continue', display: 'claude\\n--continue'}, " +
            "{key: 'codex --continue', display: 'codex\\n--continue'}, " +
            "{key: 'reasonix --continue', display: 'reasonix\\n--continue'}, " +
            "{key: 'oc --continue', display: 'oc\\n--continue'}]]";
    private static final String[] OPENHOUSE_DEFAULT_EXTRA_KEYS = new String[] {
        OPENHOUSE_EXTRA_KEYS_PAGE_ONE,
        OPENHOUSE_EXTRA_KEYS_PAGE_TWO
    };

    public TermuxTerminalExtraKeys(TermuxActivity activity, @NonNull TerminalView terminalView,
                                   TermuxTerminalViewClient termuxTerminalViewClient,
                                   TermuxTerminalSessionActivityClient termuxTerminalSessionActivityClient) {
        super(terminalView);

        mActivity = activity;
        mTermuxTerminalViewClient = termuxTerminalViewClient;
        mTermuxTerminalSessionActivityClient = termuxTerminalSessionActivityClient;

        setExtraKeys();
    }


    /**
     * Set the terminal extra keys and style.
     */
    private void setExtraKeys() {
        mExtraKeysInfo = null;
        mExtraKeysInfos = new ExtraKeysInfo[0];
        mUsingOpenHouseDefaultExtraKeys = false;

        try {
            // The mMap stores the extra key and style string values while loading properties
            // Check {@link #getExtraKeysInternalPropertyValueFromValue(String)} and
            // {@link #getExtraKeysStyleInternalPropertyValueFromValue(String)}
            String extrakeys = (String) mActivity.getProperties().getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS, true);
            String extraKeysStyle = (String) mActivity.getProperties().getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE, true);
            String userExtraKeys = mActivity.getProperties().getPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS, null, true);

            ExtraKeysConstants.ExtraKeyDisplayMap extraKeyDisplayMap = ExtraKeysInfo.getCharDisplayMapForStyle(extraKeysStyle);
            if (ExtraKeysConstants.EXTRA_KEY_DISPLAY_MAPS.DEFAULT_CHAR_DISPLAY.equals(extraKeyDisplayMap) && !TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE.equals(extraKeysStyle)) {
                Logger.logError(TermuxSharedProperties.LOG_TAG, "The style \"" + extraKeysStyle + "\" for the key \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE + "\" is invalid. Using default style instead.");
                extraKeysStyle = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE;
            }

            mUsingOpenHouseDefaultExtraKeys = userExtraKeys == null || isOpenHouseLegacyTwoRowExtraKeys(userExtraKeys);
            String[] extraKeysPages = mUsingOpenHouseDefaultExtraKeys ? OPENHOUSE_DEFAULT_EXTRA_KEYS : new String[] { extrakeys };
            mExtraKeysInfos = new ExtraKeysInfo[extraKeysPages.length];
            for (int i = 0; i < extraKeysPages.length; i++)
                mExtraKeysInfos[i] = new ExtraKeysInfo(extraKeysPages[i], extraKeysStyle, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
            mExtraKeysInfo = mExtraKeysInfos.length == 0 ? null : mExtraKeysInfos[0];
        } catch (JSONException e) {
            Logger.showToast(mActivity, "Could not load and set the \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS + "\" property from the properties file: " + e.toString(), true);
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not load and set the \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS + "\" property from the properties file: ", e);

            try {
                mExtraKeysInfo = new ExtraKeysInfo(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
                mExtraKeysInfos = new ExtraKeysInfo[] { mExtraKeysInfo };
                mUsingOpenHouseDefaultExtraKeys = false;
            } catch (JSONException e2) {
                Logger.showToast(mActivity, "Can't create default extra keys",true);
                Logger.logStackTraceWithMessage(LOG_TAG, "Could create default extra keys: ", e);
                mExtraKeysInfo = null;
                mExtraKeysInfos = new ExtraKeysInfo[0];
            }
        }
    }

    private boolean isOpenHouseLegacyTwoRowExtraKeys(String extraKeys) {
        if (extraKeys == null) return false;
        String normalized = extraKeys
            .replace("\\", "")
            .replace("\"", "'")
            .replaceAll("\\s+", "")
            .toLowerCase();
        return normalized.contains("'esc','/','-'")
            && normalized.contains("'home','up'")
            && normalized.contains("exitenter")
            && normalized.contains("proot-distrospaceloginspaceubuntuenter")
            && normalized.contains("'tab','ctrl','alt','left','down','right','keyboard'")
            && !normalized.contains("claude")
            && !normalized.contains("reasonix")
            && !normalized.contains("codex")
            && !normalized.contains("'oc");
    }

    public ExtraKeysInfo getExtraKeysInfo() {
        return mExtraKeysInfo;
    }

    public ExtraKeysInfo getExtraKeysInfo(int page) {
        if (page < 0 || page >= mExtraKeysInfos.length) return null;
        return mExtraKeysInfos[page];
    }

    public int getExtraKeysPageCount() {
        return mExtraKeysInfos.length;
    }

    public boolean isUsingOpenHouseDefaultExtraKeys() {
        return mUsingOpenHouseDefaultExtraKeys;
    }

    @SuppressLint("RtlHardcoded")
    @Override
    public void onTerminalExtraKeyButtonClick(View view, String key, boolean ctrlDown, boolean altDown, boolean shiftDown, boolean fnDown) {
        if ("KEYBOARD".equals(key)) {
            if(mTermuxTerminalViewClient != null)
                mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
        } else if ("DRAWER".equals(key)) {
            DrawerLayout drawerLayout = mTermuxTerminalViewClient.getActivity().getDrawer();
            if (drawerLayout.isDrawerOpen(Gravity.LEFT))
                drawerLayout.closeDrawer(Gravity.LEFT);
            else
                drawerLayout.openDrawer(Gravity.LEFT);
        } else if ("PASTE".equals(key)) {
            if(mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.onPasteTextFromClipboard(null);
        }  else if ("SCROLL".equals(key)) {
            TerminalView terminalView = mTermuxTerminalViewClient.getActivity().getTerminalView();
            if (terminalView != null && terminalView.mEmulator != null)
                terminalView.mEmulator.toggleAutoScrollDisabled();
        } else {
            super.onTerminalExtraKeyButtonClick(view, key, ctrlDown, altDown, shiftDown, fnDown);
        }
    }

}
