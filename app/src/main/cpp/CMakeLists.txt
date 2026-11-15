cmake_minimum_required(VERSION 3.22.1)
project(sensi_engine CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(sensi_engine SHARED
        sensi_engine.cpp
        sensi_engine.h)

target_compile_options(sensi_engine PRIVATE
        -fstack-protector-strong
        -fno-omit-frame-pointer
        -Wall
        -Wextra
        -Wno-unused-parameter)

target_link_libraries(sensi_engine
        android
        log)
