package com.wuxianpi.openhouse.core.registry;

public final class RegistryDiagnostic {
    public enum Severity { INFO, WARNING, ERROR }

    public final Severity severity;
    public final String code;
    public final String message;

    public RegistryDiagnostic(Severity severity, String code, String message) {
        this.severity = severity == null ? Severity.WARNING : severity;
        this.code = code == null ? "" : code;
        this.message = message == null ? "" : message;
    }
}
