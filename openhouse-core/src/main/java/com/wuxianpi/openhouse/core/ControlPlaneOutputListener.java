package com.wuxianpi.openhouse.core;

@FunctionalInterface
public interface ControlPlaneOutputListener {
    void onOutput(String stream, String line);
}
