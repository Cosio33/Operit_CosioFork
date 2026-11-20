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

# Weight repacking is disabled on purpose.
#
# GGML_CPU_REPACK is ON by default in ggml (ggml/CMakeLists.txt) and it makes the
# CPU backend re-order the quantized weights at load time into the "8x4" layout
# consumed by the llamafile-style kernels (ggml_gemv_q4_K_8x4_q8_K and friends).
# Those kernels are only reachable through the repack buffer type, and upstream
# does not support that path together with the multi-variant CPU backend:
# ggml-org/llama.cpp#30583 reproduces a crash on arm64 and reports that the
# model only loads with --no-repack or without CPU_ALL_VARIANTS.
#
# On this device (CUBOT KINGKONG 9 EEA / Helio G99) the crash shows up as a
# SIGSEGV inside the dot-product variant libggml-cpu-android_armv8.2_2.so:
#
#   #00 ggml_gemv_q4_K_8x4_q8_K
#   #01 ggml::cpu::repack::tensor_traits<block_q4_K, 4, 8, (ggml_type)15>::forward_mul_mat_one_chunk
#   #02 ggml::cpu::repack::tensor_traits<block_q4_K, 4, 8, (ggml_type)15>::forward_mul_mat
#   #03 ggml::cpu::repack::tensor_traits<block_q4_K, 4, 8, (ggml_type)15>::compute_forward
#   #04 ggml_cpu_extra_compute_forward
#   ...
#   #07 libomp.so (__kmp_invoke_microtask)
#
# i.e. the repack kernel reads past the end of the re-ordered weight buffer.
# The same happens with the Q6_K kernel (ggml_gemm_q6_K_8x4_q8_K).
#
# Turning the repack off does not cost the dot-product kernels: the regular
# quantized matmul path in ggml/src/ggml-cpu/arch/arm/quants.c is gated by
# __ARM_FEATURE_DOTPROD on its own, so the armv8.2 variant still uses sdot.
set(GGML_CPU_REPACK OFF CACHE BOOL "" FORCE)


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
