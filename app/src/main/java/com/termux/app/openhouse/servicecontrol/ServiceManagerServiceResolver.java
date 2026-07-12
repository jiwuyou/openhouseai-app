package com.termux.app.openhouse.servicecontrol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ServiceManagerServiceResolver {

    private static final String OPENHOUSE_COMPONENT_TAG_PREFIX = "openhouse-component:";

    private ServiceManagerServiceResolver() {
    }

    public static Resolution resolve(
        String componentId,
        List<String> requestedServiceIds,
        List<ServiceManagerService> registeredServices
    ) {
        LinkedHashMap<String, ServiceManagerService> servicesById = new LinkedHashMap<>();
        LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
        collectRegisteredServices(registeredServices, servicesById, aliases);
        addKnownCompatibilityAliases(componentId, servicesById, aliases);

        List<String> resolved = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        if (requestedServiceIds != null) {
            for (String requestedServiceId : requestedServiceIds) {
                String cleanServiceId = ServiceManagerClient.sanitizeServiceId(requestedServiceId);
                if (cleanServiceId.isEmpty()) {
                    continue;
                }
                String canonicalServiceId = aliases.get(aliasKey(cleanServiceId));
                if (canonicalServiceId != null && servicesById.containsKey(canonicalServiceId)) {
                    addUnique(resolved, canonicalServiceId);
                } else {
                    addUnique(missing, cleanServiceId);
                }
            }
        }

        if (resolved.isEmpty()) {
            String componentAlias = aliases.get(aliasKey(componentId));
            if (componentAlias != null && servicesById.containsKey(componentAlias)) {
                addUnique(resolved, componentAlias);
                missing.clear();
            }
        }

        return new Resolution(resolved, missing, servicesById);
    }

    private static void collectRegisteredServices(
        List<ServiceManagerService> registeredServices,
        LinkedHashMap<String, ServiceManagerService> servicesById,
        LinkedHashMap<String, String> aliases
    ) {
        if (registeredServices == null || registeredServices.isEmpty()) {
            return;
        }
        for (ServiceManagerService service : registeredServices) {
            if (service == null) {
                continue;
            }
            String serviceId = ServiceManagerClient.sanitizeServiceId(firstNonBlank(service.id(), service.name()));
            if (serviceId.isEmpty()) {
                continue;
            }
            if (!servicesById.containsKey(serviceId)) {
                servicesById.put(serviceId, service);
            }
            addAlias(aliases, service.id(), serviceId);
            addAlias(aliases, service.name(), serviceId);
            addAlias(aliases, service.title(), serviceId);
            addComponentTagAliases(aliases, service, serviceId);
        }
    }

    private static void addComponentTagAliases(
        LinkedHashMap<String, String> aliases,
        ServiceManagerService service,
        String serviceId
    ) {
        if (service == null || service.tags() == null) {
            return;
        }
        for (String tag : service.tags()) {
            String text = safeTrim(tag);
            if (text.regionMatches(true, 0, OPENHOUSE_COMPONENT_TAG_PREFIX, 0,
                OPENHOUSE_COMPONENT_TAG_PREFIX.length())) {
                addAlias(aliases, text.substring(OPENHOUSE_COMPONENT_TAG_PREFIX.length()), serviceId);
            }
        }
    }

    private static void addKnownCompatibilityAliases(
        String componentId,
        LinkedHashMap<String, ServiceManagerService> servicesById,
        LinkedHashMap<String, String> aliases
    ) {
        if ("pi-agent".equals(aliasKey(componentId))) {
            addAliasToTargetIfPresent(aliases, servicesById, "pi-agent", "pi-web");
        }
    }

    private static void addAliasToTargetIfPresent(
        LinkedHashMap<String, String> aliases,
        LinkedHashMap<String, ServiceManagerService> servicesById,
        String alias,
        String targetAlias
    ) {
        String aliasKey = aliasKey(alias);
        if (aliases.containsKey(aliasKey)) {
            return;
        }
        String targetServiceId = aliases.get(aliasKey(targetAlias));
        if (targetServiceId != null && servicesById.containsKey(targetServiceId)) {
            aliases.put(aliasKey, targetServiceId);
        }
    }

    private static void addAlias(LinkedHashMap<String, String> aliases, String alias, String serviceId) {
        String cleanAlias = ServiceManagerClient.sanitizeServiceId(alias);
        String cleanServiceId = ServiceManagerClient.sanitizeServiceId(serviceId);
        if (cleanAlias.isEmpty() || cleanServiceId.isEmpty()) {
            return;
        }
        String key = aliasKey(cleanAlias);
        if (!aliases.containsKey(key)) {
            aliases.put(key, cleanServiceId);
        }
    }

    private static void addUnique(List<String> values, String value) {
        String cleanValue = ServiceManagerClient.sanitizeServiceId(value);
        if (!cleanValue.isEmpty() && !values.contains(cleanValue)) {
            values.add(cleanValue);
        }
    }

    private static String firstNonBlank(String first, String second) {
        String cleanFirst = safeTrim(first);
        return cleanFirst.isEmpty() ? safeTrim(second) : cleanFirst;
    }

    private static String aliasKey(String value) {
        return safeTrim(value).toLowerCase(Locale.US);
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class Resolution {
        public final List<String> serviceIds;
        public final List<String> missingServiceIds;
        private final Map<String, ServiceManagerService> servicesById;

        private Resolution(
            List<String> serviceIds,
            List<String> missingServiceIds,
            Map<String, ServiceManagerService> servicesById
        ) {
            this.serviceIds = serviceIds == null || serviceIds.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(serviceIds));
            this.missingServiceIds = missingServiceIds == null || missingServiceIds.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(missingServiceIds));
            this.servicesById = servicesById == null || servicesById.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(servicesById));
        }

        public ServiceManagerService serviceForId(String serviceId) {
            return servicesById.get(ServiceManagerClient.sanitizeServiceId(serviceId));
        }
    }
}
