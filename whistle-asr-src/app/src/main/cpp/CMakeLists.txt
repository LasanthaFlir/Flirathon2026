cmake_minimum_required(VERSION 3.22.1)
project(qnn_whistle)

set(NEEDLE_INCLUDE_DIR "${CMAKE_SOURCE_DIR}/needle/include")
set(NEEDLE_LIB "${CMAKE_SOURCE_DIR}/needle/lib/${ANDROID_ABI}/libneedle.a")

add_library(needle_static STATIC IMPORTED)
set_target_properties(needle_static PROPERTIES IMPORTED_LOCATION ${NEEDLE_LIB})

add_library(qnn_whistle SHARED whistle_jni.cpp)
target_include_directories(qnn_whistle PRIVATE ${NEEDLE_INCLUDE_DIR})
target_link_libraries(qnn_whistle needle_static android log dl m)
