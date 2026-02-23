cmake_minimum_required(VERSION 3.22.1)
project(aigentik_llama)

# Position independent code — required for static libs in shared lib
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Snapdragon 8 Gen 3 (Cortex-X4) correct instruction set
# armv8.4-a covers all ARMv9 features available via NDK
# i8mm = INT8 matrix multiply — used heavily by llama.cpp GEMM kernels
# NOTE: armv8-a was causing SIGBUS/SIGSEGV in ggml_compute_forward_mul_mat
#       because dotprod+fp16 on armv8-a base generates misaligned math kernels
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -march=armv8.4-a+dotprod+fp16+i8mm")
set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}   -O3 -march=armv8.4-a+dotprod+fp16+i8mm")

set(LLAMA_SRC_DIR "${CMAKE_SOURCE_DIR}/llama_src")

# llama.cpp build options — disable everything not needed on Android
set(LLAMA_BUILD_TESTS   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER  OFF CACHE BOOL "" FORCE)
set(GGML_METAL          OFF CACHE BOOL "" FORCE)
set(GGML_CUDA           OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN         OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP         OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS   OFF CACHE BOOL "" FORCE)

# Use CPU backend only — no GPU
add_compile_definitions(GGML_USE_CPU)

add_subdirectory(${LLAMA_SRC_DIR} llama_build)

add_library(aigentik_llama SHARED llama_jni.cpp)

target_include_directories(aigentik_llama PRIVATE
    ${LLAMA_SRC_DIR}/include
    ${LLAMA_SRC_DIR}/ggml/include
    ${LLAMA_SRC_DIR}/src/../include
    ${LLAMA_SRC_DIR}/ggml/src/../include
)

target_link_libraries(aigentik_llama
    llama
    ggml
    android
    log
)
