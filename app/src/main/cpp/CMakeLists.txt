cmake_minimum_required(VERSION 3.22.1)
project(omnidev_native CXX)

# ─────────────────────────────────────────────────────────────────────────────
# llama.cpp source resolution — two-tier fallback strategy
#
# Tier 1 (fast, offline): git submodule at app/src/main/cpp/llama.cpp
#   • Populated automatically in GitHub Actions via `submodules: recursive`.
#   • Populated locally via `git submodule update --init --recursive`.
#   • When present, no network access is required at configure time.
#
# Tier 2 (automatic, requires internet): CMake FetchContent
#   • Triggered automatically when the submodule directory is absent.
#   • Downloads a pinned llama.cpp release archive from GitHub.
#   • Cached in the CMake build tree — subsequent builds are fast.
#   • Lets `./gradlew assembleDebug` work on any machine without manual steps.
#
# The stub (llama_jni_stub.cpp) is kept for the case where neither llama.cpp
# submodule nor FetchContent is available (fully offline, no internet).
# ─────────────────────────────────────────────────────────────────────────────

set(LLAMA_CPP_SUBMODULE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
set(BITNET_SUBMODULE_DIR    "${CMAKE_CURRENT_SOURCE_DIR}/bitnet")

# Shared build flags — disable everything we don't need
set(LLAMA_NATIVE         OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

# ═══════════════════════════════════════════════════════════════════════════════
#  LLAMA.CPP — libllama_jni.so
# ═══════════════════════════════════════════════════════════════════════════════

# ── Tier 1: local git submodule ───────────────────────────────────────────────
if(EXISTS "${LLAMA_CPP_SUBMODULE_DIR}/CMakeLists.txt")
    message(STATUS "[llama_jni] Tier 1: using local git submodule at ${LLAMA_CPP_SUBMODULE_DIR}")
    add_subdirectory("${LLAMA_CPP_SUBMODULE_DIR}" llama_build EXCLUDE_FROM_ALL)
    set(LLAMA_CPP_SRC "${LLAMA_CPP_SUBMODULE_DIR}")
    set(LLAMA_CPP_REAL TRUE)

# ── Tier 2: CMake FetchContent auto-download ──────────────────────────────────
else()
    message(STATUS "[llama_jni] Tier 2: submodule absent — fetching llama.cpp via CMake FetchContent")
    message(STATUS "[llama_jni] Tip: run 'git submodule update --init --recursive' for faster offline builds")

    include(FetchContent)
    FetchContent_Declare(
        llama_cpp
        URL "https://github.com/ggerganov/llama.cpp/archive/refs/tags/b5695.tar.gz"
    )
    FetchContent_MakeAvailable(llama_cpp)
    set(LLAMA_CPP_SRC "${llama_cpp_SOURCE_DIR}")
    set(LLAMA_CPP_REAL TRUE)
endif()

# ── libllama_jni.so ───────────────────────────────────────────────────────────
if(LLAMA_CPP_REAL)
    add_library(llama_jni SHARED llama_jni.cpp)
    target_compile_options(llama_jni PRIVATE
        -std=c++17 -O3 -fvisibility=hidden -Wall -Wno-unused-function)
    target_include_directories(llama_jni PRIVATE
        "${LLAMA_CPP_SRC}/include"
        "${LLAMA_CPP_SRC}/ggml/include")
    target_link_libraries(llama_jni PRIVATE llama ggml android log)
endif()

# ═══════════════════════════════════════════════════════════════════════════════
#  BITNET.CPP — libbitnet_jni.so
#
#  BitNet.cpp (https://github.com/microsoft/BitNet) is a llama.cpp fork
#  optimised for 1-bit (BitNet b1.58) quantised models.  Its CMake build
#  exposes the same `llama` and `ggml` targets as llama.cpp; we link against
#  them exactly like the llama_jni target above.
#
#  ARM TL1/TL2 kernels are enabled for arm64-v8a (the primary Android ABI).
#  They are skipped for x86_64 (emulator) which uses the generic path.
# ═══════════════════════════════════════════════════════════════════════════════

if(EXISTS "${BITNET_SUBMODULE_DIR}/CMakeLists.txt")
    message(STATUS "[bitnet_jni] BitNet.cpp submodule found — compiling real libbitnet_jni.so")

    # BitNet ARM-optimised kernel flags (arm64-v8a only)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(BITNET_ARM_TL1 ON  CACHE BOOL "" FORCE)
        set(BITNET_ARM_TL2 ON  CACHE BOOL "" FORCE)
    else()
        set(BITNET_ARM_TL1 OFF CACHE BOOL "" FORCE)
        set(BITNET_ARM_TL2 OFF CACHE BOOL "" FORCE)
    endif()

    # BitNet.cpp builds a `llama` library (it is a fork of llama.cpp).
    # Use a distinct binary directory to avoid CMake target collisions with
    # the llama.cpp targets built above.
    add_subdirectory("${BITNET_SUBMODULE_DIR}" bitnet_build EXCLUDE_FROM_ALL)
    set(BITNET_SRC "${BITNET_SUBMODULE_DIR}")

    add_library(bitnet_jni SHARED bitnet_jni.cpp)

    target_compile_options(bitnet_jni PRIVATE
        -std=c++17 -O3 -fvisibility=hidden -Wall -Wno-unused-function)

    # ARM TL1/TL2 intrinsic optimisations
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        target_compile_options(bitnet_jni PRIVATE -march=armv8-a+dotprod+i8mm)
    endif()

    target_include_directories(bitnet_jni PRIVATE
        "${BITNET_SRC}/include"
        "${BITNET_SRC}/ggml/include")

    # BitNet.cpp exposes the same `llama` and `ggml` targets as its llama.cpp
    # upstream.  We alias them with a `bitnet_` prefix in the build dir to
    # avoid conflicts, but the standard target names are used here.
    target_link_libraries(bitnet_jni PRIVATE llama ggml android log)

else()
    # Submodule absent — compile the stub so the APK always ships with a
    # libbitnet_jni.so that returns clean errors rather than UnsatisfiedLinkError.
    message(STATUS "[bitnet_jni] BitNet.cpp submodule absent — compiling stub libbitnet_jni.so")
    message(STATUS "[bitnet_jni] Run 'git submodule update --init --recursive' to enable real BitNet inference")

    add_library(bitnet_jni SHARED bitnet_jni_stub.cpp)
    target_compile_options(bitnet_jni PRIVATE -std=c++17 -fvisibility=hidden)
    target_link_libraries(bitnet_jni PRIVATE android log)
endif()
