# Preserve all classes and member names that declare native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# ChiaKey JNI bridge
-keep class com.fskeyboard.ime.engine.ChiaKeyNative { *; }

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
