package com.termux.app.openhouse.servicecontrol;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

public final class ServiceManagerControlClient {

    private final ServiceManagerClient client;

    public ServiceManagerControlClient(Context context) {
        this.client = new ServiceManagerClient();
    }

    public ServiceManagerControlClient(String baseUrl) {
        this.client = new ServiceManagerClient(baseUrl);
    }

    public List<ServiceManagerService> listServices() {
        ServiceManagerResult result = client.listServices();
        if (!result.success) {
            throw new ServiceManagerControlException(result.message);
        }
        return result.services;
    }

    public ServiceManagerServiceStatus getStatus(String serviceId) {
        String cleanServiceId = ServiceManagerClient.sanitizeServiceId(serviceId);
        ServiceManagerResult result = client.getStatus(cleanServiceId);
        if (!result.success) {
            throw new ServiceManagerControlException(result.message);
        }
        return new ServiceManagerServiceStatus(cleanServiceId, result);
    }

    public ServiceManagerResult getStatusResult(String serviceId) {
        return client.getStatus(serviceId);
    }

    public ServiceManagerActionResult runAction(String serviceId, String action) {
        return new ServiceManagerActionResult(client.runAction(serviceId, action));
    }

    public List<ServiceManagerLogEntry> getLogs(String serviceId, int limit) {
        ServiceManagerResult result = client.getLogs(serviceId, limit);
        if (!result.success) {
            throw new ServiceManagerControlException(result.message);
        }
        List<ServiceManagerLogEntry> entries = new ArrayList<>();
        for (ServiceManagerLogLine line : result.logLines) {
            entries.add(new ServiceManagerLogEntry(line));
        }
        return entries;
    }

    public static final class ServiceManagerControlException extends RuntimeException {
        ServiceManagerControlException(String message) {
            super(message == null || message.trim().isEmpty() ? "service-manager request failed" : message);
        }
    }
}
