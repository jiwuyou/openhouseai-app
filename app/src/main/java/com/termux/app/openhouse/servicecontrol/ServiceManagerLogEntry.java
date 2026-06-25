package com.termux.app.openhouse.servicecontrol;

public final class ServiceManagerLogEntry {

    private final String time;
    private final String stream;
    private final String message;
    private final String raw;

    ServiceManagerLogEntry(ServiceManagerLogLine line) {
        this.time = line == null ? "" : line.time;
        this.stream = line == null ? "" : line.stream;
        this.message = line == null ? "" : line.message;
        this.raw = line == null ? "" : line.raw;
    }

    public String time() {
        return time;
    }

    public String stream() {
        return stream;
    }

    public String message() {
        return message;
    }

    public String raw() {
        return raw;
    }
}
