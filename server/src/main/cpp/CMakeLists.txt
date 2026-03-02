cmake_minimum_required(VERSION 3.22.1)

project(scrcpy_v4l2)

add_library(scrcpy_v4l2 SHARED
        v4l2_capture_jni.cpp)

find_library(log-lib log)

target_link_libraries(scrcpy_v4l2
        ${log-lib})
