# AXON native build (spec §18, decision D8).
#
# §18 gives the flags as "verified": arm64-v8a, android-26, -DLLAMA_VULKAN=ON,
# -DBUILD_SHARED_LIBS=ON. All are honoured except the Vulkan default, which D8
# downgrades from a setting to a measurement: on the target device (Mali-G52 MC2,
# driver r32p1) llama.cpp's Vulkan backend is likely slower than the CPU backend
# and has a history of correctness problems on drivers of that vintage.
#
# So AXON_VULKAN is a build flag, defaulting OFF, and Phase 1 builds both ways
# and compares. §14.2 already reports latency and thermal ceiling, so the
# comparison is one of the results rather than a detour.

cmake_minimum_required(VERSION 3.22.1)
project(axon_llama CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(AXON_VULKAN "Build the ggml Vulkan backend (see docs/DECISIONS.md D8)" OFF)

# ---------------------------------------------------------------------------
# Always optimise the native code, even in a debug APK.
# ---------------------------------------------------------------------------
#
# AGP compiles the debug variant with CMAKE_BUILD_TYPE=Debug, which means -O0.
# For ordinary JNI glue that is a non-issue; for ggml it is catastrophic, because
# the quantised matmul kernels live or die on vectorisation and inlining. On the
# Helio G85 target, an -O0 build took **over ten minutes** for a single 32-token
# generation from a 0.8 GB model and was SIGKILLed by the OS before finishing;
# nothing in the logs said "you are running unoptimised", it simply looked like a
# hang.
#
# This is a trap worth naming in the thesis, because every latency figure in §14
# would have been wrong by an order of magnitude and still looked plausible —
# slow is exactly what one expects from a budget phone.
#
# Debug symbols are kept: the goal is optimised code that is still debuggable,
# not a release build wearing a debug label.
set(CMAKE_C_FLAGS_DEBUG   "-O3 -g -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -g -DNDEBUG")
set(CMAKE_C_FLAGS_RELEASE   "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")

# ---------------------------------------------------------------------------
# llama.cpp
# ---------------------------------------------------------------------------

set(LLAMA_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/llama.cpp)
if(NOT EXISTS ${LLAMA_ROOT}/include/llama.h)
    message(FATAL_ERROR
        "llama.cpp submodule missing at ${LLAMA_ROOT}.\n"
        "Run: git submodule update --init --recursive")
endif()

# Nothing but the library — the CLI tools, server and tests are desktop targets
# and would fail or waste minutes cross-compiling for arm64.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    ON  CACHE BOOL "" FORCE)

# The target CPU is 2x Cortex-A75 + 6x Cortex-A55: ARMv8.2-A with dotprod
# (asimddp confirmed in /proc/cpuinfo), but no i8mm and no SVE. dotprod is
# exactly what the Q4_K kernels use, so this is the single flag that most
# affects throughput on this device.
set(GGML_NATIVE  OFF CACHE BOOL "" FORCE)
set(GGML_DOTPROD ON  CACHE BOOL "" FORCE)

if(AXON_VULKAN)
    message(STATUS "AXON: building WITH Vulkan backend (D8 comparison arm)")
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
else()
    message(STATUS "AXON: building CPU-only (D8 default)")
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory(${LLAMA_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp EXCLUDE_FROM_ALL)

# ---------------------------------------------------------------------------
# The AXON JNI bridge
# ---------------------------------------------------------------------------

add_library(axon_llama SHARED axon_llama.cpp)

target_link_libraries(axon_llama
    PRIVATE
        llama
        ggml
        android
        log)

target_compile_options(axon_llama PRIVATE -O3 -fvisibility=hidden)
