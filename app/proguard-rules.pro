# GameLingo-Mobile release rules.

# Models parsed from remote JSON (latest.json, AI providers) must survive minification.
-keep class com.gamelingo.mobile.core.update.** { *; }
-keep class com.gamelingo.mobile.data.remote.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static ** Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static ** Companion;
}
-keepclassmembers class <2>.Companion {
    kotlinx.serialization.KSerializer serializer(...);
    kotlinx.serialization.KSerializer[] childSerializers();
}
-keepclasseswithmembers class com.gamelingo.mobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep line numbers for crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ONNX Runtime + LiteRT-LM resolve Java classes BY NAME from JNI (OrtException,
# LiteRtLmJniException, value/info types). R8 renaming or removing them turns a
# catchable native error into a fatal FindClass abort: this exact path crashed
# v0.4.0 on device (decoder error -> native throw -> class not found -> SIGABRT).
-keep class ai.onnxruntime.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
