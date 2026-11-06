# Preserve all classes and member names that declare native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Rime JNI bridge
-keep class com.fskeyboard.ime.bridge.RimeResponse { <init>(...); }
-keep class com.fskeyboard.ime.bridge.RimeNative { *; }

# Rust lexicon JNI bridge
-keep class com.fskeyboard.ime.engine.RustLexiconNative { *; }

# ChiaKey JNI bridge
-keep class com.fskeyboard.ime.engine.ChiaKeyNative { *; }

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
