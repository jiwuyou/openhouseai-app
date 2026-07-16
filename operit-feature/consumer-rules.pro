# Keep Shizuku API types used by the hosted Operit integration.
-keep class rikka.shizuku.** { *; }

# Keep Shower Binder IPC types aligned with shower-server.jar.
-keep class com.ai.assistance.shower.ShowerBinderContainer { *; }
-keep class com.ai.assistance.shower.IShowerService { *; }
-keep class com.ai.assistance.shower.IShowerVideoSink { *; }

# Keep Parcelable creators used across Binder boundaries.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep the QuickJS reflection binding object.
-keep class com.ai.assistance.operit.core.tools.javascript.JsEngine$JsToolCallInterface { *; }

# Optional desktop/image/XML dependencies referenced by Operit's document tooling.
-dontwarn com.caverock.androidsvg.SVG
-dontwarn com.caverock.androidsvg.SVGParseException
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn java.awt.**
-dontwarn java.awt.color.**
-dontwarn java.awt.geom.**
-dontwarn java.awt.image.**
-dontwarn javax.imageio.**
-dontwarn javax.lang.model.**
-dontwarn javax.xml.stream.**
-dontwarn net.sf.saxon.**
-dontwarn org.apache.batik.**
-dontwarn org.osgi.framework.**
-dontwarn org.tukaani.xz.**
-dontwarn org.apache.poi.xslf.draw.**
-dontwarn org.apache.poi.xslf.usermodel.**
-dontwarn org.apache.poi.util.**
-dontwarn org.apache.pdfbox.**
-dontwarn org.apache.fontbox.**
-dontwarn org.apache.commons.compress.archivers.sevenz.**
-dontwarn org.apache.xmlbeans.**
-dontwarn pl.droidsonroids.gif.**
-dontwarn reactor.blockhound.integration.BlockHoundIntegration
-dontwarn io.netty.util.internal.Hidden$NettyBlockHoundIntegration
