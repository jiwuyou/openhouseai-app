# QuickJsNativeHostDispatcher is called from quickjs_jni.cpp through GetMethodID.
# R8 cannot infer this callback edge from Java/Kotlin bytecode.
-keep interface com.ai.assistance.operit.core.tools.javascript.QuickJsNativeRuntime$HostBridge {
    public java.lang.String onCall(java.lang.String, java.lang.String);
}

-keepclassmembers class * implements com.ai.assistance.operit.core.tools.javascript.QuickJsNativeRuntime$HostBridge {
    public java.lang.String onCall(java.lang.String, java.lang.String);
}

-keepclassmembers class com.ai.assistance.operit.core.tools.javascript.QuickJsNativeHostDispatcher {
    public java.lang.String onCall(java.lang.String, java.lang.String);
}
