cmake_minimum_required(VERSION 3.22.1)
project(fskeyboard)

# RIME_ROOT is passed from Gradle:
#   third_party/librime/include/rime_api.h          (BSD header from librime)
#   third_party/librime/jniLibs/<abi>/librime.so    (and its NEEDED libraries)
if(NOT DEFINED RIME_ROOT OR RIME_ROOT STREQUAL "")
    message(FATAL_ERROR "Set RIME_ROOT to third_party/librime")
endif()

set(RIME_HEADER "${RIME_ROOT}/include/rime_api.h")
set(RIME_LIBRARY "${RIME_ROOT}/jniLibs/${ANDROID_ABI}/librime.so")

if(NOT EXISTS "${RIME_HEADER}")
    message(FATAL_ERROR "Missing ${RIME_HEADER}")
endif()
if(NOT EXISTS "${RIME_LIBRARY}")
    message(FATAL_ERROR "Missing ${RIME_LIBRARY}")
endif()

add_library(fskeyboard SHARED rime_jni.cpp)
target_include_directories(fskeyboard PRIVATE "${RIME_ROOT}/include")
target_compile_features(fskeyboard PRIVATE cxx_std_17)

add_library(rime SHARED IMPORTED)
set_target_properties(rime PROPERTIES
    IMPORTED_LOCATION "${RIME_LIBRARY}"
    IMPORTED_NO_SONAME TRUE)

find_library(log-lib log)
target_link_libraries(fskeyboard rime ${log-lib})
