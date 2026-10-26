cmake_minimum_required(VERSION 3.22.1)
project(flyagent CXX C)
# never ship unoptimized inference code, whatever build type Gradle passes
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_CXX_STANDARD 17)

# ggml writes dynamically loaded CPU variants to the runtime dir; send them where Gradle packages .so files
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY})
include(FetchContent)
# llama.cpp (MIT), pinned tag; supports Qwen3. Only the core library is built.
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)   # avoid shipping libomp; ggml uses its own thread pool
# Runtime CPU dispatch: build ARMv8.0 / v8.2+dotprod / v8.2+fp16 / v8.6+i8mm variants of the ggml CPU backend
# and load the best one the phone supports (a baseline-only build is several times slower on modern phones).
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
FetchContent_Declare(llama GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git GIT_TAG b6999 GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(llama)

add_library(flyagent SHARED connectome.cpp llm_core.cpp jni_bridge.cpp)

# Linux workspace (PRoot + Ubuntu), from Mobile Harness
add_subdirectory(mh)
target_link_libraries(flyagent llama android log)
target_compile_options(flyagent PRIVATE -O3)
