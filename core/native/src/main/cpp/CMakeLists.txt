cmake_minimum_required(VERSION 3.22)
project(rawline_native CXX C)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# LibRaw is fetched from libraw.org (GitHub is not reachable from the build sandbox) and pinned by hash.
include(FetchContent)
FetchContent_Declare(libraw_src
    URL https://www.libraw.org/data/LibRaw-0.22.2.tar.gz
    URL_HASH SHA256=de86b035655accff8d4010f1a221fdf50d353cb7b1422ba26f14a0db92612cfa
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_MakeAvailable(libraw_src)

file(GLOB_RECURSE LIBRAW_SOURCES
    ${libraw_src_SOURCE_DIR}/src/*.cpp)
# Optional third-party integrations that need extra SDKs.
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX "/integration/|_ph\\.cpp$")

add_library(raw SHARED ${LIBRAW_SOURCES})
target_include_directories(raw PUBLIC ${libraw_src_SOURCE_DIR})
target_compile_definitions(raw PRIVATE LIBRAW_BUILDLIB)
target_compile_options(raw PRIVATE -O2 -w)
set_target_properties(raw PROPERTIES OUTPUT_NAME "raw")

# Shader sources are embedded at configure time.
set(SHADER_HEADER ${CMAKE_CURRENT_BINARY_DIR}/shader_sources.h)
file(GLOB SHADER_FILES ${CMAKE_CURRENT_SOURCE_DIR}/shaders/*)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${SHADER_FILES})
execute_process(COMMAND ${CMAKE_COMMAND} -DSHADER_DIR=${CMAKE_CURRENT_SOURCE_DIR}/shaders -DOUT=${SHADER_HEADER}
                -P ${CMAKE_CURRENT_SOURCE_DIR}/gen_shaders.cmake)

add_library(rawline_jni SHARED jni_bridge.cpp jni_engine.cpp rw2_preview.cpp raw_decode.cpp engine/engine.cpp)
target_include_directories(rawline_jni PRIVATE ${CMAKE_CURRENT_BINARY_DIR} ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(rawline_jni PRIVATE -O2)
target_link_libraries(rawline_jni PRIVATE raw GLESv3 EGL android log jnigraphics)
