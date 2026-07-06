package com.termux.app.openhouse.desktop;

import com.termux.app.openhouse.servicecontrol.ServiceManagerClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class DesktopAppServices {

    private DesktopAppServices() {
    }

    static List<String> resolveServiceIds(List<String> serviceNames, List<String> serviceRefs) {
        List<String> out = new ArrayList<>();
        addServiceNames(out, serviceNames);
        addServiceNames(out, ServiceManagerClient.serviceIdsFromRefs(serviceRefs));
        return out.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(out);
    }

    static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            String text = safeTrim(value);
            if (text.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(text);
        }
        return builder.toString();
    }

    private static void addServiceNames(List<String> out, List<String> values) {
        if (out == null || values == null) {
            return;
        }
        for (String value : values) {
            String serviceId = ServiceManagerClient.sanitizeServiceId(value);
            if (!serviceId.isEmpty() && !out.contains(serviceId)) {
                out.add(serviceId);
            }
        }
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
