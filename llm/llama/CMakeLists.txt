cmake_minimum_required(VERSION 3.22.1)

project("llama_jni")

include("${CMAKE_CURRENT_LIST_DIR}/../../cmake/operit_git_source.cmake")

# The Android Gradle plugin sets CMAKE_BUILD_TYPE=Debug for the debug and clone
# variants. CMake's Debug flags are "-g" only, so the inference kernels end up
# compiled at -O0: ggml_vec_dot_q4_0_q8_0 measures 2592 bytes in that build
# against 696 bytes with -O2, and the quantized matmul path that runs on every
# generated token becomes several times slower on device. llama.cpp only
# defaults to Release when CMAKE_BUILD_TYPE is unset, so the Debug build type is
# replaced here to keep the native engine optimized in every variant.
if (CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

operit_prepare_git_source(
    OPERIT_LLAMA_CPP_SOURCE_DIR
    OPERIT_LLAMA_CPP_BINARY_DIR
    llama_cpp
    "https://github.com/ggml-org/llama.cpp.git"
    # v0.8.0 (b11960): first upstream release with LiquidAI LFM3 support
    # (LFM3-1.7B / LFM3-VL-2B / LFM3-Audio-2B / LFM3-Omni-2B), ggml-org/llama.cpp#31252
    "8642541b255bfb5dc39cd351a178be09013a55d6"
)

add_subdirectory("${OPERIT_LLAMA_CPP_SOURCE_DIR}" "${OPERIT_LLAMA_CPP_BINARY_DIR}" EXCLUDE_FROM_ALL)

add_library(
    LlamaWrapper
    SHARED
    src/main/cpp/llama_jni_stub.cpp
)

target_compile_definitions(LlamaWrapper PRIVATE OPERIT_HAS_LLAMA_CPP=1)
target_include_directories(LlamaWrapper PRIVATE "${OPERIT_LLAMA_CPP_SOURCE_DIR}")
target_include_directories(LlamaWrapper PRIVATE "${OPERIT_LLAMA_CPP_SOURCE_DIR}/common")
target_include_directories(LlamaWrapper PRIVATE "${OPERIT_LLAMA_CPP_SOURCE_DIR}/include")
target_include_directories(LlamaWrapper PRIVATE "${OPERIT_LLAMA_CPP_SOURCE_DIR}/ggml/include")
target_include_directories(LlamaWrapper PRIVATE "${OPERIT_LLAMA_CPP_SOURCE_DIR}/vendor")
target_link_libraries(LlamaWrapper llama llama-common)

target_link_libraries(
    LlamaWrapper
    android
    log
)

# 16KB page size support (Android 15+ requirement)
target_link_options(LlamaWrapper PRIVATE "-Wl,-z,max-page-size=16384")
