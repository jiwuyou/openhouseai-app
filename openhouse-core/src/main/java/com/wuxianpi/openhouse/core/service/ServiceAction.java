package com.wuxianpi.openhouse.core.service;

import java.util.Locale;

public enum ServiceAction {
    START("start"), STOP("stop"), RESTART("restart"), REPAIR("repair");

    private final String apiName;
    ServiceAction(String apiName) { this.apiName = apiName; }
    public String apiName() { return apiName; }

    public static ServiceAction parse(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.US);
        for (ServiceAction action : values()) if (action.apiName.equals(clean)) return action;
        return null;
    }
}
