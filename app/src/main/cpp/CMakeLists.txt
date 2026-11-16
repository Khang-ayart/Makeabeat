cmake_minimum_required(VERSION 3.22.1)
project(bandaudio LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(bandaudio SHARED
        engine_core.cpp
        noise_reducer.cpp
        audio_engine.cpp
        jni_bridge.cpp)

# target_compile_options đứng SAU cờ của từng cấu hình, nên -O3 thắng -O0 của bản debug.
# Callback âm thanh chạy -O0 sẽ bị rè/xrun, vì vậy luôn tối ưu cả ở debug.
target_compile_options(bandaudio PRIVATE -O3 -Wall -Wextra)

# Android 15+ (trang bộ nhớ 16 KB).
target_link_options(bandaudio PRIVATE -Wl,-z,max-page-size=16384)

target_link_libraries(bandaudio oboe::oboe log)
