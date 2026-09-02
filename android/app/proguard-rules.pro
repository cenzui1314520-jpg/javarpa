# Keep Rhino liveconnect entry points
-keep class com.rpa.engine.api.** { *; }
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# Rhino 通过反射调用 js_* 内建函数与 Host 回调
-keepclassmembers class com.rpa.engine.engine.RhinoScriptEngine {
    public static ** js_*;
}
-keep class com.rpa.engine.engine.RhinoScriptEngine$Host { *; }
-keep class * implements com.rpa.engine.engine.RhinoScriptEngine$Host { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
