cmake_minimum_required(VERSION 3.22.1)
project(xmbnative CXX)

set(CMAKE_CXX_STANDARD 17)
set(AT3 ${CMAKE_CURRENT_SOURCE_DIR}/at3_standalone)

add_library(at3_standalone STATIC
        ${AT3}/atrac.cpp
        ${AT3}/atrac3.cpp
        ${AT3}/atrac3plus.cpp
        ${AT3}/atrac3plusdec.cpp
        ${AT3}/atrac3plusdsp.cpp
        ${AT3}/get_bits.cpp
        ${AT3}/compat.cpp
        ${AT3}/fft.cpp
        ${AT3}/mem.cpp)
target_include_directories(at3_standalone PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/shim ${AT3})
target_compile_options(at3_standalone PRIVATE -O2 -w)

add_library(xmbnative SHARED atrac_jni.cpp)
target_include_directories(xmbnative PRIVATE ${AT3})
target_link_libraries(xmbnative at3_standalone log)
