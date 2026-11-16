cmake_minimum_required(VERSION 3.22.1)
project(xyu_rime)
set(PREFIX "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.native/install/${ANDROID_ABI}")
add_library(xyu_rime SHARED bridge.cpp)
target_include_directories(xyu_rime PRIVATE "${PREFIX}/include")
target_link_libraries(xyu_rime PRIVATE
    "${PREFIX}/lib/librime.a" "${PREFIX}/lib/libyaml-cpp.a"
    "${PREFIX}/lib/libleveldb.a" "${PREFIX}/lib/libopencc.a"
    "${PREFIX}/lib/libmarisa.a" log)
target_link_options(xyu_rime PRIVATE "-Wl,-z,max-page-size=16384")
