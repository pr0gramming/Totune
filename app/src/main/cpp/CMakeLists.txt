cmake_minimum_required(VERSION 3.22.1)
project(totune CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(totune SHARED jni_bridge.cpp)
target_compile_options(totune PRIVATE -O3 -Wall -Wno-unused-parameter)
target_link_libraries(totune aaudio log)
