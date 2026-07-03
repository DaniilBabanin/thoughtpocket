# whisper.cpp JNI entry points must not be renamed
-keep class com.thoughtpocket.WhisperEngine { *; }
-keep class com.thoughtpocket.TranscribeCallback { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# libsherpa-onnx-jni.so looks these classes up by name (FindClass/GetFieldID); the vendored
# bare jar carries no consumer rules, so keep them all or the Moonshine path breaks under R8.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# Keeping all of sherpa-onnx surfaces SherpaAudioConvertTools' reference to this absent
# optional helper lib (R8 used to strip the class unseen); the app never calls that path.
-dontwarn com.bihe0832.android.lib.audio.AudioUtils

# AI Edge RAG SDK (Gecko) references compile-only annotations (AutoValue) and
# protobuf-lite internal annotation classes that aren't on the runtime classpath.
# They're build-time only, so silence the R8 missing-class errors.
-dontwarn com.google.auto.value.**
-dontwarn com.google.protobuf.**

