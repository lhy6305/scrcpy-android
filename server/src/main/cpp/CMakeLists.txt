cmake_minimum_required(VERSION 3.22.1)

project(amlogic_scrcpy_v4l2)

add_library(amlogic_scrcpy_v4l2 SHARED
        amlogic_v4l2_capture_jni.cpp)

find_library(log-lib log)

target_link_libraries(amlogic_scrcpy_v4l2
        ${log-lib})
