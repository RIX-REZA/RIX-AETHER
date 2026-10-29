# RIX JNI bridge.
#
# One shared object that owns no VPN logic of its own: it resolves the two
# prebuilt libraries at runtime with dlopen/dlsym and forwards calls.
#
# dlopen (not link-time symbols) because:
#   * the two libraries are built by different toolchains in CI and dropped into
#     jniLibs as opaque artifacts;
#   * a missing or ABI-mismatched library then surfaces as a clean JNI error
#     instead of a process abort during System.loadLibrary;
#   * nothing here needs to know their SONAMEs ahead of time.

cmake_minimum_required(VERSION 3.22.1)
project(rixjni C)

add_library(rixjni SHARED jni_bridge.c)

target_compile_options(rixjni PRIVATE
        -O2 -fvisibility=hidden -fno-exceptions -Wall -Wextra -Wno-unused-parameter)

find_library(LOG_LIB log)
find_library(DL_LIB dl)

target_link_libraries(rixjni PRIVATE ${LOG_LIB} ${DL_LIB})