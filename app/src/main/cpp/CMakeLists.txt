cmake_minimum_required(VERSION 3.22.1)
project(llama_jni CXX)

# ── llama.cpp submodule path ──────────────────────────────────────────────────
# Add the submodule with:
#   git submodule add https://github.com/ggerganov/llama.cpp.git \
#                     app/src/main/cpp/llama.cpp
#   git submodule update --init --recursive
set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")

# ── Real build (llama.cpp submodule present) ──────────────────────────────────
if(EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(STATUS "llama.cpp submodule found — building real inference library")

    # Build only the core libraries; skip tests, examples and the server
    set(LLAMA_NATIVE       OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS   OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER  OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS   OFF CACHE BOOL "" FORCE)

    add_subdirectory("${LLAMA_CPP_DIR}" llama_build EXCLUDE_FROM_ALL)

    add_library(llama_jni SHARED llama_jni.cpp)

    target_compile_options(llama_jni PRIVATE
        -std=c++17
        -O3
        -fvisibility=hidden
        -Wall
        -Wno-unused-function)

    target_include_directories(llama_jni PRIVATE
        "${LLAMA_CPP_DIR}/include"
        "${LLAMA_CPP_DIR}/ggml/include")

    target_link_libraries(llama_jni
        PRIVATE
            llama
            ggml
            android
            log)

# ── Stub build (llama.cpp submodule missing) ──────────────────────────────────
else()
    message(WARNING
        "llama.cpp submodule NOT found at ${LLAMA_CPP_DIR}.\n"
        "Building stub libllama_jni.so — inference will not work.\n"
        "Run: git submodule update --init --recursive")

    add_library(llama_jni SHARED llama_jni_stub.cpp)

    target_compile_options(llama_jni PRIVATE -std=c++17)

    target_link_libraries(llama_jni PRIVATE android log)
endif()
