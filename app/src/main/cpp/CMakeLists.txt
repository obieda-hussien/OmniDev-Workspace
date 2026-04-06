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
        URL "https://github.com/ggml-org/llama.cpp/archive/refs/tags/b8233.tar.gz"
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
else()
    # Stub fallback — compiled when neither submodule nor FetchContent is available
    # (e.g. fully offline builds). LlamaCppInferenceEngine detects this via nativeIsStub()
    # and disables inference rather than crashing with UnsatisfiedLinkError.
    message(WARNING "[llama_jni] No llama.cpp source available — compiling stub (inference disabled)")
    add_library(llama_jni SHARED llama_jni_stub.cpp)
    target_link_libraries(llama_jni PRIVATE android log)
endif()
