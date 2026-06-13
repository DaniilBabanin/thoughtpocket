# whisper.cpp JNI entry points must not be renamed
-keep class com.soundscript.WhisperEngine { *; }
-keep class com.soundscript.TranscribeCallback { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
