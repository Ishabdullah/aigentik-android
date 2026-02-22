cmake_minimum_required(VERSION 3.22.1)
project("aigentik")

# Build settings
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ARM64 optimizations for S24 Ultra
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -march=armv8-a+dotprod+fp16")

# llama.cpp source directory
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama_src)

# Disable llama.cpp features we don't need
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Add llama.cpp as subdirectory
add_subdirectory(${LLAMA_DIR} llama_build)

# Our JNI bridge library
add_library(
    aigentik_llama
    SHARED
    llama_jni.cpp
)

# Link libraries
target_link_libraries(
    aigentik_llama
    llama
    ggml
    android
    log
)

# Include llama.cpp headers
target_include_directories(
    aigentik_llama
    PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
)
