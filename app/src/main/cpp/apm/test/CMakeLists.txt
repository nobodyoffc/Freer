# A host test of the echo canceller: build on a Mac with
#   cmake -S app/src/main/cpp/apm/test -B /tmp/apm-test && cmake --build /tmp/apm-test && /tmp/apm-test/apm_test
# The app itself builds ../ for Android through app/src/main/cpp.
cmake_minimum_required(VERSION 3.22.1)
project(apm_test C CXX)
set(CMAKE_CXX_STANDARD 17)
add_subdirectory(.. apm)
add_executable(apm_test apm_test.cc)
target_link_libraries(apm_test webrtc_apm)
if(APPLE)
    target_link_libraries(apm_test "-framework Foundation")
endif()
