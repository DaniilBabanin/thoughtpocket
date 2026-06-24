import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val useVulkan = project.findProperty("whispershare.vulkan")?.toString()?.toBoolean() ?: false

// The AI Edge RAG SDK (Gecko spike) needs Guava >= 28 (Futures.submit), but a transitive
// dep pins guava strictly to 27.0.1. Force a modern Guava so the SDK resolves at runtime.
configurations.all {
    resolutionStrategy {
        force("com.google.guava:guava:33.3.1-android")
    }
}

android {
    namespace = "com.thoughtpocket"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.thoughtpocket"
        minSdk = 31
        targetSdk = 35
        versionCode = 6
        versionName = "0.1.5-pre"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // arm64 for devices; x86_64 so it also runs on a desktop emulator.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DWHISPERSHARE_VULKAN=${if (useVulkan) "ON" else "OFF"}"
                )
                cppFlags += listOf("-std=c++17", "-O3", "-fvisibility=hidden")
            }
        }
    }

    // Public release keystore committed in-repo (password documented in .gitignore) so CI
    // and local builds sign identically with no secrets. Guarded so a stripped checkout
    // without it still builds (falls back to debug signing).
    val releaseKeystore = file("release.keystore")
    signingConfigs {
        if (releaseKeystore.exists()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = "whispershare"
                keyAlias = "thoughtpocket"
                keyPassword = "whispershare"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseKeystore.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
        // Release-like (R8 + shrink), but debug-signed so it installs over an existing debug build
        // without wiping data — for VALID on-device perf measurement (debug builds are misleading).
        create("benchmark") {
            initWith(getByName("release"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }

    androidResources {
        // .tflite must stay uncompressed so MediaPipe can mmap it from assets.
        noCompress += "tflite"
    }

    lint {
        // The 2026.05 Compose BOM ships lint rules built against a newer Lint API than AGP 8.7.2's
        // engine, which crashes lintVitalRelease (IncompatibleClassChangeError) and breaks release
        // builds. Skip lint on release so the release pipeline works; remove when AGP is bumped (see
        // the open AGP Dependabot PR). Lint still runs on debug: ./gradlew :app:lintDebug
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        }
        jniLibs {
            useLegacyPackaging = false
            // whisper.cpp and MediaPipe both ship libc++_shared.so — keep one.
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // On-device LLM (Gemma) for AI tagging — LiteRT-LM is the runtime .litertlm models
    // are built for (and what AI Edge Gallery uses), with a working GPU path.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")

    // On-device text embeddings (semantic relate / search / clustering) — Gecko 110m via the
    // AI Edge RAG SDK. Validated to beat MediaPipe USE (see ScaleTest). Needs the Guava force above.
    implementation("com.google.ai.edge.localagents:localagents-rag:0.3.0")
    // The RAG SDK's generated protos need the protobuf-lite runtime (previously supplied
    // transitively by MediaPipe, now removed) — provide it explicitly.
    implementation("com.google.protobuf:protobuf-javalite:4.35.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // UI smoke tests + scroll-perf driving (300-note list jank regression).
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
