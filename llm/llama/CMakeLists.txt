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

# Multi-variant CPU backend so the int8 dot-product kernels can be used.
#
# ggml gates the dot-product kernels behind the compile-time macro
# __ARM_FEATURE_DOTPROD (ggml/src/ggml-cpu/arch/arm/quants.c), so a single
# library built with "+dotprod" would execute the sdot instruction on every
# device and die with SIGILL on CPUs that do not implement it. Upstream solves
# that by shipping one shared library per feature set and picking the best
# supported one at runtime through the score function in
# ggml/src/ggml-cpu/arch/arm/cpu-feats.cpp, which requires the backend to be
# built as a dynamically loaded module (GGML_BACKEND_DL) and the per-variant
# build to be enabled (GGML_CPU_ALL_VARIANTS).
#
# Upstream already provides the Android variants, so no patch is needed:
#   android_armv8.0_1, android_armv8.2_1 (DOTPROD), android_armv8.2_2,
#   android_armv8.6_1, android_armv9.0_1, android_armv9.2_1, android_armv9.2_2
# (ggml/src/CMakeLists.txt). The variants are loaded at runtime by
# llama_backend_init() through ggml_backend_load_all(); on Android the search
# path has to be provided explicitly, see nativeLibraryDir() in
# src/main/cpp/llama_jni_stub.cpp.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)

# 16KB page size support (Android 15+ requirement). With BUILD_SHARED_LIBS the
# engine is no longer a single object file, so every shared library and every
# dynamically loaded backend module has to be aligned as well. These flags are
# applied before add_subdirectory() so they reach the llama.cpp targets.
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_MODULE_LINKER_FLAGS "${CMAKE_MODULE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

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
    # dladdr() is used to locate the directory that holds the packaged native
    # libraries, which is where the dynamically loaded CPU backend variants
    # are installed.
    dl
)

# 16KB page size support (Android 15+ requirement)
target_link_options(LlamaWrapper PRIVATE "-Wl,-z,max-page-size=16384")
