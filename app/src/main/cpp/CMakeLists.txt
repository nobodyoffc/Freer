cmake_minimum_required(VERSION 3.22.1)
project(freervoice C CXX)

# libopus 1.5.2, vendored from the xiph release tarball (sha256
# 65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1)
# without its dnn/ models: DRED, OSCE and deep PLC stay off.
# No test programs from any vendored library: abseil turns BUILD_TESTING on otherwise.
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)
add_subdirectory(opus)

# The codec runs 50 times a second per stream: optimise it in debug builds too.
target_compile_options(opus PRIVATE -O2)

# WebRTC's audio processing, for the app's own echo canceller: see apm/CMakeLists.txt.
add_subdirectory(apm)

add_library(freervoice SHARED opus_jni.c aaudio_jni.c apm_jni.cc)
target_compile_options(freervoice PRIVATE -O2 -Wall)
target_link_libraries(freervoice opus webrtc_apm aaudio log)
