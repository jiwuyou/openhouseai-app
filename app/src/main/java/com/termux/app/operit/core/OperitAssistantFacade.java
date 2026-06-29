package com.termux.app.operit.core;

import android.content.Context;

import com.termux.app.operit.init.OperitFeatureInitializer;
import com.termux.app.operit.init.OperitFeatureSnapshot;
import com.termux.app.operit.runtime.OperitRuntimeBridge;

public final class OperitAssistantFacade {

    private final OperitToolRouter toolRouter;
    private final OperitFeatureInitializer featureInitializer;
    private final Context initializationContext;

    public OperitAssistantFacade(Context context, OperitRuntimeBridge runtimeBridge) {
        this(context, new OperitToolRouter(runtimeBridge));
    }

    public OperitAssistantFacade(OperitRuntimeBridge runtimeBridge) {
        this(null, new OperitToolRouter(runtimeBridge));
    }

    public OperitAssistantFacade(Context context, OperitToolRouter toolRouter) {
        this(context, toolRouter, OperitFeatureInitializer.getInstance());
    }

    public OperitAssistantFacade(OperitToolRouter toolRouter) {
        this(null, toolRouter, OperitFeatureInitializer.getInstance());
    }

    OperitAssistantFacade(
        Context context,
        OperitToolRouter toolRouter,
        OperitFeatureInitializer featureInitializer
    ) {
        if (toolRouter == null) {
            throw new IllegalArgumentException("toolRouter must not be null");
        }
        if (featureInitializer == null) {
            throw new IllegalArgumentException("featureInitializer must not be null");
        }
        this.toolRouter = toolRouter;
        this.featureInitializer = featureInitializer;
        if (context == null) {
            this.initializationContext = null;
        } else {
            Context applicationContext = context.getApplicationContext();
            this.initializationContext = applicationContext == null ? context : applicationContext;
        }
    }

    public OperitAssistantResponse submit(String input) {
        try {
            ensureFeatureSnapshot();
            return toolRouter.route(input).withFeatureSnapshot(featureInitializer.snapshot());
        } catch (Exception e) {
            return OperitAssistantResponse.failure(
                input,
                "Operit core adapter 调用失败。",
                e
            ).withFeatureSnapshot(featureInitializer.snapshot());
        }
    }

    public OperitFeatureSnapshot initialize(Context context) {
        return featureInitializer.initialize(context);
    }

    public OperitFeatureSnapshot getFeatureSnapshot() {
        return featureInitializer.snapshot();
    }

    public boolean isInitialized() {
        return featureInitializer.isInitialized();
    }

    private OperitFeatureSnapshot ensureFeatureSnapshot() {
        if (initializationContext != null) {
            return featureInitializer.initialize(initializationContext);
        }
        return featureInitializer.snapshot();
    }
}
