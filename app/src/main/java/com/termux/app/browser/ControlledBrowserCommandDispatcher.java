package com.termux.app.browser;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

import java.util.ArrayDeque;
import java.util.Queue;

public final class ControlledBrowserCommandDispatcher {

    public interface CommandCallback {
        void onResult(@NonNull ControlledBrowserCommandResult result);
    }

    public interface CommandHandler {
        void handleBrowserCommand(@NonNull Bundle command, @NonNull CommandCallback callback);
    }

    private static final String LOG_TAG = "ControlledBrowserBus";
    private static final long DEFAULT_NO_HANDLER_TIMEOUT_MS = 4000L;
    private static final ControlledBrowserCommandDispatcher INSTANCE =
        new ControlledBrowserCommandDispatcher();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Queue<PendingCommand> pendingCommands = new ArrayDeque<>();

    private Context appContext;
    private CommandHandler activeHandler;
    private boolean delivering;

    private ControlledBrowserCommandDispatcher() {}

    @NonNull
    public static ControlledBrowserCommandDispatcher getInstance() {
        return INSTANCE;
    }

    public void enqueue(@NonNull Context context, @Nullable Bundle command) {
        if (command == null) {
            return;
        }
        Context applicationContext = context.getApplicationContext();
        mainHandler.post(() -> {
            appContext = applicationContext;
            PendingCommand pending = new PendingCommand(command);
            pendingCommands.offer(pending);
            Logger.logDebug(LOG_TAG, "enqueue command=" + pending.commandName()
                + " requestId=" + pending.requestIdForLog());
            scheduleNoHandlerTimeoutLocked(pending);
            drainLocked();
        });
    }

    public void registerHandler(@NonNull Context context, @NonNull CommandHandler handler) {
        Context applicationContext = context.getApplicationContext();
        mainHandler.post(() -> {
            appContext = applicationContext;
            activeHandler = handler;
            Logger.logDebug(LOG_TAG, "register handler pending=" + pendingCommands.size());
            drainLocked();
        });
    }

    public void unregisterHandler(@NonNull CommandHandler handler) {
        mainHandler.post(() -> {
            if (activeHandler == handler) {
                activeHandler = null;
                Logger.logDebug(LOG_TAG, "unregister handler pending=" + pendingCommands.size());
            }
        });
    }

    private void drainLocked() {
        if (delivering || activeHandler == null || pendingCommands.isEmpty()) {
            return;
        }
        PendingCommand pending = pendingCommands.poll();
        if (pending.completed) {
            drainLocked();
            return;
        }
        Bundle command = pending.command;
        CommandHandler handler = activeHandler;
        delivering = true;
        Logger.logDebug(LOG_TAG, "drain command=" + pending.commandName()
            + " requestId=" + pending.requestIdForLog());
        try {
            handler.handleBrowserCommand(command, result -> mainHandler.post(() -> {
                pending.completed = true;
                Logger.logDebug(LOG_TAG, "write result command=" + pending.commandName()
                    + " requestId=" + pending.requestIdForLog()
                    + " ok=" + result.isSuccessful());
                writeResult(command, result);
                delivering = false;
                drainLocked();
            }));
        } catch (RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Browser command handler failed", e);
            pending.completed = true;
            writeError(command, "command_dispatch_failed",
                e.getMessage() == null ? "Browser command dispatch failed" : e.getMessage());
            delivering = false;
            drainLocked();
        }
    }

    private void scheduleNoHandlerTimeoutLocked(@NonNull PendingCommand pending) {
        mainHandler.postDelayed(
            () -> failIfNoHandlerLocked(pending),
            pending.noHandlerTimeoutMs);
    }

    private void failIfNoHandlerLocked(@NonNull PendingCommand pending) {
        if (pending.completed || activeHandler != null) {
            return;
        }
        if (!pendingCommands.remove(pending)) {
            return;
        }
        pending.completed = true;
        Logger.logError(LOG_TAG, "no handler timeout command=" + pending.commandName()
            + " requestId=" + pending.requestIdForLog()
            + " waitedMs=" + (SystemClock.elapsedRealtime() - pending.enqueuedAtMs));
        writeError(
            pending.command,
            "browser_handler_unavailable",
            "Controlled browser handler was not available");
    }

    private void writeResult(
        @NonNull Bundle command,
        @NonNull ControlledBrowserCommandResult result
    ) {
        if (appContext != null) {
            ControlledBrowserRpcFiles.writeResultIfRequested(appContext, command, result);
        }
    }

    private void writeError(
        @NonNull Bundle command,
        @NonNull String code,
        @Nullable String message
    ) {
        if (appContext == null) {
            return;
        }
        ControlledBrowserRpcFiles.writeErrorResult(
            appContext,
            command.getString(ControlledBrowserContract.EXTRA_RESULT_FILE),
            command.getString(ControlledBrowserContract.EXTRA_REQUEST_ID),
            code,
            message == null ? "Browser command dispatch failed" : message);
    }

    private static final class PendingCommand {

        final Bundle command;
        final long enqueuedAtMs;
        final long noHandlerTimeoutMs;
        boolean completed;

        PendingCommand(@NonNull Bundle command) {
            this.command = new Bundle(command);
            this.enqueuedAtMs = SystemClock.elapsedRealtime();
            this.noHandlerTimeoutMs = resolveNoHandlerTimeoutMs(this.command);
        }

        @NonNull
        String commandName() {
            String commandName = command.getString(ControlledBrowserContract.EXTRA_COMMAND);
            return commandName == null || commandName.trim().isEmpty() ? "<request>" : commandName;
        }

        @NonNull
        String requestIdForLog() {
            String requestId = command.getString(ControlledBrowserContract.EXTRA_REQUEST_ID);
            return requestId == null || requestId.trim().isEmpty() ? "-" : requestId;
        }

        private static long resolveNoHandlerTimeoutMs(@NonNull Bundle command) {
            long timeoutMs = DEFAULT_NO_HANDLER_TIMEOUT_MS;
            if (command.containsKey(ControlledBrowserContract.EXTRA_TIMEOUT_MS)) {
                Object rawTimeout = command.get(ControlledBrowserContract.EXTRA_TIMEOUT_MS);
                if (rawTimeout instanceof Number) {
                    timeoutMs = ((Number) rawTimeout).longValue();
                } else if (rawTimeout != null) {
                    try {
                        timeoutMs = Long.parseLong(rawTimeout.toString());
                    } catch (NumberFormatException ignored) {
                        timeoutMs = DEFAULT_NO_HANDLER_TIMEOUT_MS;
                    }
                }
            }
            timeoutMs = Math.min(DEFAULT_NO_HANDLER_TIMEOUT_MS, Math.max(1L, timeoutMs));
            return timeoutMs;
        }
    }
}
