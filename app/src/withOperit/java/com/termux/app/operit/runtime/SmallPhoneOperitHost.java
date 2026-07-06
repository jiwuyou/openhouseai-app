package com.termux.app.operit.runtime;

import android.content.Context;

import com.ai.assistance.operit.host.OperitHostCommandResult;
import com.ai.assistance.operit.host.OperitHostContract;
import com.ai.assistance.operit.host.OperitHostProvider;
import com.ai.assistance.operit.host.OperitHostServiceManagerResult;
import com.ai.assistance.operit.host.lifecycle.OperitHostLifecycle;
import com.ai.assistance.operit.host.lifecycle.OperitHostLifecycleConfig;

import kotlin.coroutines.Continuation;

public final class SmallPhoneOperitHost implements OperitHostContract {

    private static volatile SmallPhoneOperitHost instance;

    private final Context applicationContext;
    private final OperitRuntimeBridge runtimeBridge;

    private SmallPhoneOperitHost(Context context) {
        Context appContext = context.getApplicationContext();
        applicationContext = appContext == null ? context : appContext;
        runtimeBridge = new OperitRuntimeBridge();
    }

    public static SmallPhoneOperitHost install(Context context) {
        return installProviderOnly(context);
    }

    public static SmallPhoneOperitHost installProviderOnly(Context context) {
        SmallPhoneOperitHost host = instance;
        if (host == null) {
            synchronized (SmallPhoneOperitHost.class) {
                host = instance;
                if (host == null) {
                    host = new SmallPhoneOperitHost(context);
                    instance = host;
                }
            }
        }
        OperitHostProvider.INSTANCE.install(host);
        return host;
    }

    public static SmallPhoneOperitHost installAndInitializeLifecycle(Context context) {
        SmallPhoneOperitHost host = installProviderOnly(context);
        OperitHostLifecycle.INSTANCE.initialize(
            host.applicationContext,
            host,
            new OperitHostLifecycleConfig()
        );
        return host;
    }

    @Override
    public Context getApplicationContext() {
        return applicationContext;
    }

    @Override
    public Object executeTermuxCommand(
        String command,
        long timeoutMs,
        Continuation<? super OperitHostCommandResult> continuation
    ) {
        OperitCommandResult result = runtimeBridge.executeTermux(command, timeoutMs);
        return toHostCommandResult(result);
    }

    @Override
    public Object executeUbuntuCommand(
        String command,
        long timeoutMs,
        Continuation<? super OperitHostCommandResult> continuation
    ) {
        OperitCommandResult result = runtimeBridge.executeUbuntu(command, timeoutMs);
        return toHostCommandResult(result);
    }

    private OperitHostCommandResult toHostCommandResult(OperitCommandResult result) {
        return new OperitHostCommandResult(
            result.command,
            result.exitCode,
            result.stdout,
            result.stderr,
            result.error,
            result.timedOut,
            result.durationMs
        );
    }

    @Override
    public Object queryServiceManagerHealth(
        Continuation<? super OperitHostServiceManagerResult> continuation
    ) {
        return toHostServiceManagerResult(runtimeBridge.getServiceManagerHealth());
    }

    @Override
    public Object queryServiceManagerStatus(
        String serviceId,
        Continuation<? super OperitHostServiceManagerResult> continuation
    ) {
        return toHostServiceManagerResult(runtimeBridge.getServiceManagerStatus(serviceId));
    }

    private OperitHostServiceManagerResult toHostServiceManagerResult(OperitServiceManagerResult result) {
        return new OperitHostServiceManagerResult(
            result.success,
            result.code,
            result.url,
            result.body,
            result.message,
            result.serviceId,
            result.state,
            result.provider,
            result.pid,
            result.serviceUrl,
            result.error,
            result.durationMs
        );
    }
}
