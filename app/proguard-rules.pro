# whisper.cpp JNI entry points must not be renamed
-keep class com.soundscript.WhisperEngine { *; }
-keep class com.soundscript.TranscribeCallback { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# AI Edge RAG SDK (Gecko) references compile-only annotations (AutoValue) and
# protobuf-lite internal annotation classes that aren't on the runtime classpath.
# They're build-time only, so silence the R8 missing-class errors.
-dontwarn com.google.auto.value.**
-dontwarn com.google.protobuf.**

