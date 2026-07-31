#
# vcat-d (Video Codec Acid Test)
#
# SPDX-FileCopyrightText: Copyright (C) 2020-2025 vcat-d authors and RoncaTech
# SPDX-License-Identifier: GPL-3.0-or-later
#
# This file is part of vcat-d.
#
# vcat-d is free software: you can redistribute it and/or modify it under the
# terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later
# version. See <https://www.gnu.org/licenses/gpl-3.0.html>.
#
# For proprietary/commercial use cases, a written GPL-3.0 waiver or a separate
# commercial license is required from RoncaTech LLC. Contact: legal@roncatech.com
#

cmake_minimum_required(VERSION 3.22)
project(vcat_dav1d_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# tighter visibility (optional)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

if(NOT ANDROID)
    message(FATAL_ERROR "Android/NDK build only")
endif()

# =========================
# Bundled prebuilt dav1d (per-ABI)
# app/src/main/cpp -> ../../.. = app ; third_party/dav1d holds include/ + lib/<abi>/
# =========================
set(DAV1D_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/dav1d")
set(DAV1D_INCLUDE_DIR "${DAV1D_ROOT}/include")
set(DAV1D_STATIC      "${DAV1D_ROOT}/lib/${ANDROID_ABI}/libdav1d.a")

message(STATUS "ABI='${ANDROID_ABI}'")
message(STATUS "  DAV1D_STATIC='${DAV1D_STATIC}'")

if(NOT EXISTS "${DAV1D_STATIC}")
    message(FATAL_ERROR "Static dav1d not found: ${DAV1D_STATIC}")
endif()

# =========================
# Sources
# =========================
add_library(vcat_dav1d_jni SHARED dav1d_jni.cc)

target_include_directories(vcat_dav1d_jni PRIVATE "${DAV1D_INCLUDE_DIR}")

# NDK/system libs
find_library(log_lib     log)
find_library(android_lib android)
find_library(z_lib       z)

# Link static STL to avoid shipping libc++_shared.so
target_link_libraries(vcat_dav1d_jni
        PRIVATE
        "${DAV1D_STATIC}"
        ${log_lib}
        ${android_lib}
        ${z_lib}
        c++_static
)

# 32-bit often needs libatomic
if(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    find_library(atomic_lib atomic)
    if(atomic_lib)
        target_link_libraries(vcat_dav1d_jni PRIVATE ${atomic_lib})
    endif()
endif()

# Catch missing symbols at link time (fail fast)
# 16KB page-size alignment required for Android 15+ devices
target_link_options(vcat_dav1d_jni PRIVATE
        -Wl,--no-undefined
        -Wl,-z,max-page-size=16384
)
