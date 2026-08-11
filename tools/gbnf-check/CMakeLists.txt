# Host build of gbnf-check. Not part of the Android build — this runs on the
# dev machine and in CI, where stderr is readable and no phone is required.

cmake_minimum_required(VERSION 3.22)
project(gbnf_check CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/llama.cpp)

set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp EXCLUDE_FROM_ALL)

add_executable(gbnf-check main.cpp)

# src/ is on the include path because this links llama.cpp's *internal* grammar
# parser. The public entry point needs a llama_vocab, which needs a model file;
# grammar syntax is model-independent, so requiring a 3 GB download would keep
# this check out of CI for no benefit.
target_include_directories(gbnf-check PRIVATE
    ${LLAMA_ROOT}/src
    ${LLAMA_ROOT}/include
    ${LLAMA_ROOT}/ggml/include)

target_link_libraries(gbnf-check PRIVATE llama ggml-base)
