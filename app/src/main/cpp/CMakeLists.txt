
cmake_minimum_required(VERSION 3.22.1)

project("shin")

add_subdirectory(unpaircore)
add_library(shin SHARED
NativeLoader.cpp)

target_link_libraries(shin

    android
    log)
