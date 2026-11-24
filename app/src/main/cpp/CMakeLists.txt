# Step 3 (AAudio Exclusive) — native side.
# Scaffold (v1.1.38): proves the NDK/CMake toolchain builds in CI, the
# .so is packaged into the APK, and JNI loads. The full AAudio output
# engine (stream open/write/close at the track's native rate) comes in
# the next version; this file only exposes a trivial availability probe.
cmake_minimum_required(VERSION 3.22.1)

project("mio_aaudio")

add_library(mio_aaudio SHARED mio_aaudio.cpp)

# AAudio is a stable NDK API since API 26 (our minSdk), linked from the
# NDK's prebuilt libaaudio. No third-party dependency.
find_library(log-lib log)

target_link_libraries(mio_aaudio aaudio ${log-lib})
