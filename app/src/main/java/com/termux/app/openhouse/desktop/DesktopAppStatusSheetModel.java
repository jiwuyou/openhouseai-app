package com.termux.app.openhouse.desktop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DesktopAppStatusSheetModel {

    public final DesktopAppDescriptor app;
    public final DesktopAppStatus status;
    public final List<DesktopAppAction> actions;
    public final List<String> detailLines;
    public final List<String> recentLogLines;

    public DesktopAppStatusSheetModel(
        DesktopAppDescriptor app,
        DesktopAppStatus status,
        List<DesktopAppAction> actions,
        List<String> detailLines,
        List<String> recentLogLines
    ) {
        this.app = app;
        this.status = status;
        this.actions = immutableActions(actions);
        this.detailLines = immutableStrings(detailLines);
        this.recentLogLines = immutableStrings(recentLogLines);
    }

    public String title() {
        if (app != null) {
            return app.displayTitle();
        }
        return status == null || status.title.isEmpty() ? "应用状态" : status.title;
    }

    public String headline() {
        return status == null ? "状态未知" : status.headline;
    }

    private static List<DesktopAppAction> immutableActions(List<DesktopAppAction> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<DesktopAppAction> out = new ArrayList<>();
        for (DesktopAppAction value : values) {
            if (value != null) {
                out.add(value);
            }
        }
        return out.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(out);
    }

    private static List<String> immutableStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (String value : values) {
            String text = value == null ? "" : value.trim();
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return out.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(out);
    }
}
