cmake_minimum_required(VERSION 3.22.1)
project(omnidev_native CXX)

# ─────────────────────────────────────────────────────────────────────────────
# llama.cpp source resolution — two tiers, no stub
#
# Tier 1 (fast, offline): a populated source tree at app/src/main/cpp/llama.cpp
#   • Normally a git submodule (`git submodule update --init --recursive`).
#   • When present, no network access is required at configure time.
#
# Tier 2 (requires internet): CMake FetchContent
#   • Used when the directory above is absent. This snapshot declares the submodule in
#     .gitmodules but does not track a gitlink, so this is the path CI and clean checkouts take.
#   • Downloads the pinned llama.cpp release archive (tag b8233) from GitHub and caches it in the
#     CMake build tree.
#
# There is deliberately NO silent stub fallback. FetchContent failures are fatal at configure
# time, so a build that cannot obtain llama.cpp fails loudly instead of shipping a library that
# cannot run inference. (The old llama_jni_stub.cpp was unreachable and has been removed.)
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
endif()

# ── libllama_jni.so ───────────────────────────────────────────────────────────
add_library(llama_jni SHARED llama_jni.cpp)
target_compile_options(llama_jni PRIVATE
    -std=c++17 -O3 -fvisibility=hidden -Wall -Wno-unused-function)
target_include_directories(llama_jni PRIVATE
    "${LLAMA_CPP_SRC}/include"
    "${LLAMA_CPP_SRC}/ggml/include")
target_link_libraries(llama_jni PRIVATE llama ggml android log)
