cmake_minimum_required(VERSION 3.22.1)
project(fskeyboard LANGUAGES C CXX)

# ChiaKeyCore reads CMAKE_CXX_BYTE_ORDER while configuring expat.
# The Android header patch is applied here so a fresh submodule and an
# already patched tree both configure.
set(FS_REPO "${CMAKE_CURRENT_SOURCE_DIR}/../../../..")
set(CHIAKEY_DIR "${FS_REPO}/third_party/chiakey")
set(CHIAKEY_PATCH "${FS_REPO}/third_party/patches/chiakey-android.patch")
execute_process(
    COMMAND git apply --reverse --check "${CHIAKEY_PATCH}"
    WORKING_DIRECTORY "${CHIAKEY_DIR}"
    RESULT_VARIABLE CHIAKEY_PATCH_PRESENT
)
if(NOT CHIAKEY_PATCH_PRESENT EQUAL 0)
    execute_process(
        COMMAND git apply "${CHIAKEY_PATCH}"
        WORKING_DIRECTORY "${CHIAKEY_DIR}"
        RESULT_VARIABLE CHIAKEY_PATCH_RESULT
    )
    if(NOT CHIAKEY_PATCH_RESULT EQUAL 0)
        message(FATAL_ERROR "Failed to apply ${CHIAKEY_PATCH}")
    endif()
endif()

set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
add_subdirectory(
    "${CHIAKEY_DIR}/ChiaKey-Source/Frameworks/ChiaKeyCore"
    "${CMAKE_CURRENT_BINARY_DIR}/chiakey-core"
)
target_compile_options(chiakey_core PUBLIC -fexceptions -frtti)

add_library(fs_chiakey SHARED chiakey_jni.cpp)
target_compile_features(fs_chiakey PRIVATE cxx_std_17)
target_link_libraries(fs_chiakey PRIVATE chiakey_core)
find_library(log-lib log)
target_link_libraries(fs_chiakey PRIVATE ${log-lib})

option(RIME_ENABLED "Link the optional librime bridge" OFF)
if(RIME_ENABLED)
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
    target_link_libraries(fskeyboard rime ${log-lib})
endif()
