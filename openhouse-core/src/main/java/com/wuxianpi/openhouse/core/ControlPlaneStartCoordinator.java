package com.wuxianpi.openhouse.core;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Process-wide single-flight coordinator and bounded in-memory startup log. */
public final class ControlPlaneStartCoordinator {
    private static final int MAX_LOG_LINES = 200;
    private static final Object LOCK = new Object();
    private static final Deque<String> LOG_LINES = new ArrayDeque<>();
    private static CompletableFuture<ControlPlaneCommandResult> inFlight;
    private static ControlPlaneCommandResult latestResult =
        new ControlPlaneCommandResult(-1, "", "No control-plane start has run in this process");

    private ControlPlaneStartCoordinator() {}

    public static ControlPlaneCommandResult start(
        ControlPlaneBridge bridge,
        String source,
        ControlPlaneOutputListener listener
    ) {
        if (bridge == null) {
            return new ControlPlaneCommandResult(127, "", "ControlPlaneBridge is unavailable");
        }

        CompletableFuture<ControlPlaneCommandResult> current;
        boolean owner = false;
        synchronized (LOCK) {
            current = inFlight;
            if (current == null) {
                current = new CompletableFuture<>();
                inFlight = current;
                owner = true;
                LOG_LINES.clear();
                appendLocked("[" + timestamp() + "][source] " + safe(source));
            }
        }

        if (owner) {
            ControlPlaneCommandResult result;
            try {
                result = bridge.start((stream, line) -> {
                    String entry = "[" + safe(stream) + "] " + safe(line);
                    synchronized (LOCK) {
                        appendLocked(entry);
                    }
                    if (listener != null) listener.onOutput(stream, line);
                });
                if (result == null) {
                    result = new ControlPlaneCommandResult(1, "", "ControlPlaneBridge returned no result");
                }
            } catch (Throwable error) {
                result = new ControlPlaneCommandResult(1, "", safeMessage(error));
            }
            synchronized (LOCK) {
                latestResult = result;
                appendLocked("[exit] " + result.exitCode);
                current.complete(result);
                inFlight = null;
            }
            return result;
        }

        try {
            return current.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new ControlPlaneCommandResult(130, "", "Interrupted while another start was running");
        } catch (Exception error) {
            return new ControlPlaneCommandResult(1, "", safeMessage(error));
        }
    }

    public static ControlPlaneCommandResult start(ControlPlaneBridge bridge, String source) {
        return start(bridge, source, null);
    }

    public static String latestTranscript() {
        synchronized (LOCK) {
            return String.join("\n", LOG_LINES);
        }
    }

    public static ControlPlaneCommandResult latestResult() {
        synchronized (LOCK) {
            return latestResult;
        }
    }

    public static boolean isStartInFlight() {
        synchronized (LOCK) {
            return inFlight != null;
        }
    }

    private static void appendLocked(String line) {
        LOG_LINES.addLast(line);
        while (LOG_LINES.size() > MAX_LOG_LINES) LOG_LINES.removeFirst();
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "Unknown control-plane start failure";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName()
            : message.trim();
    }
}
