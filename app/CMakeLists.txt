cmake_minimum_required(VERSION 3.22.1)
project("thoughtpocket" LANGUAGES C CXX)

option(WHISPERSHARE_VULKAN "Enable Vulkan GPU backend in whisper.cpp" OFF)

include(FetchContent)

# Pin to a known-good whisper.cpp tag. Bump as needed.
# v1.7.6+ fixes vulkan-shaders-gen cross-compile so Vulkan builds on Android.
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG        v1.8.4
    GIT_SHALLOW    TRUE
)

# whisper.cpp build options
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

if(WHISPERSHARE_VULKAN)
    message(STATUS "WhisperShare: enabling Vulkan backend")
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)

    # whisper.cpp's Vulkan path needs glslc to compile shader sources.
    # The Android NDK ships one under shader-tools/<host>/. Locate it so the
    # build works on a fresh checkout without a system-wide Vulkan SDK.
    if(NOT Vulkan_GLSLC_EXECUTABLE)
        foreach(host_tag IN ITEMS linux-x86_64 darwin-x86_64 darwin-arm64 windows-x86_64)
            set(_candidate "${ANDROID_NDK}/shader-tools/${host_tag}/glslc")
            if(EXISTS "${_candidate}")
                set(Vulkan_GLSLC_EXECUTABLE "${_candidate}" CACHE FILEPATH "" FORCE)
                break()
            endif()
            if(EXISTS "${_candidate}.exe")
                set(Vulkan_GLSLC_EXECUTABLE "${_candidate}.exe" CACHE FILEPATH "" FORCE)
                break()
            endif()
        endforeach()
        message(STATUS "WhisperShare: Vulkan_GLSLC_EXECUTABLE=${Vulkan_GLSLC_EXECUTABLE}")
    endif()

    # whisper.cpp >= 1.7.5 uses vulkan.hpp (Vulkan-Hpp) which the NDK doesn't ship.
    # Pull the headers via FetchContent and put them ahead of the NDK sysroot so
    # the C headers (Vulkan-Headers submodule) match the C++ wrapper version.
    FetchContent_Declare(
        vulkan_hpp
        GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Hpp.git
        GIT_TAG        v1.3.290
        GIT_SHALLOW    TRUE
    )
    FetchContent_GetProperties(vulkan_hpp)
    if(NOT vulkan_hpp_POPULATED)
        FetchContent_Populate(vulkan_hpp)
    endif()
    include_directories(BEFORE SYSTEM
        "${vulkan_hpp_SOURCE_DIR}/Vulkan-Headers/include"
        "${vulkan_hpp_SOURCE_DIR}"
    )
endif()

# ARMv8.2 fp16 + dotprod — Tensor G4 supports both.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_options(-march=armv8.2-a+fp16+dotprod)
    # llama b9964's ggml ignores inherited flags: with GGML_NATIVE=OFF it only
    # emits SIMD kernels when GGML_CPU_ARM_ARCH is set (else armv8-a baseline —
    # measured 0.16 tok/s vs 4.6 for the prebuilt on Pixel 9 Pro XL, 2026-07-11).
    # No +i8mm: armv8.2 devices (Pixel 6 class) would SIGILL, and whisper shares
    # this ggml.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
endif()

# --- llama.cpp (coder feature spike) ---------------------------------------
# Made available BEFORE whisper so llama's (newer) bundled ggml wins the
# `if(NOT TARGET ggml)` guard both projects carry; whisper then builds against
# llama's ggml via target propagation. If whisper transcription misbehaves,
# that's the ggml ABI-skew failure — fall back to a vendored libllama.so
# (sherpa-onnx precedent). b9964 ships Qwen3.5 arch (Ornith needs >= b9616).
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b9964
    GIT_SHALLOW    TRUE
)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(llama)

FetchContent_MakeAvailable(whisper)

add_library(${CMAKE_PROJECT_NAME} SHARED
    src/main/cpp/whisper_jni.cpp
    src/main/cpp/llama_jni.cpp
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${whisper_SOURCE_DIR}/include
    ${llama_SOURCE_DIR}/include
    ${llama_SOURCE_DIR}/ggml/include
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(${CMAKE_PROJECT_NAME}
    whisper
    llama
    ${log-lib}
    ${android-lib}
)

# 16 KB page alignment (Google Play requirement). NDK r27's new toolchain doesn't inject this,
# so our .so links with 4 KB LOAD segments; force 16 KB. common-page-size matters for lld's
# .relro_padding. (All dependency .so files are already 16 KB-aligned.)
target_link_options(${CMAKE_PROJECT_NAME} PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

if(WHISPERSHARE_VULKAN)
    target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE WHISPERSHARE_VULKAN=1)
endif()
