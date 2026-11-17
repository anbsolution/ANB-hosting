cmake_minimum_required(VERSION 3.22.1)
project(anb_python_runtime LANGUAGES CXX)

if(NOT DEFINED ANB_PYTHON_RUNTIME_DIR)
    message(FATAL_ERROR "ANB_PYTHON_RUNTIME_DIR must point to the staged CPython packages")
endif()

set(PYTHON_VERSION "3.14")

if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
    set(PYTHON_ABI_DIR "${ANB_PYTHON_RUNTIME_DIR}/jniLibs/${ANDROID_ABI}")
    set(PYTHON_INCLUDE_DIR "${ANB_PYTHON_RUNTIME_DIR}/prefix/${ANDROID_ABI}/include/python${PYTHON_VERSION}")
    set(PYTHON_SHARED_LIBRARY "${PYTHON_ABI_DIR}/libpython${PYTHON_VERSION}.so")

    foreach(required_path IN ITEMS "${PYTHON_INCLUDE_DIR}/Python.h" "${PYTHON_SHARED_LIBRARY}")
        if(NOT EXISTS "${required_path}")
            message(FATAL_ERROR "Required CPython package file is missing: ${required_path}")
        endif()
    endforeach()

    add_library(cpython SHARED IMPORTED GLOBAL)
    set_target_properties(cpython PROPERTIES IMPORTED_LOCATION "${PYTHON_SHARED_LIBRARY}")

    add_library(anbpython SHARED BundledPythonRuntime.cpp)
    target_include_directories(anbpython PRIVATE "${PYTHON_INCLUDE_DIR}")
    target_link_libraries(anbpython PRIVATE cpython)
else()
    # Keep the existing app installable on 32-bit-only devices. Those ABIs
    # have no official CPython package, so they intentionally get no Python API.
    add_library(anbpython SHARED PythonRuntimeUnavailable.cpp)
endif()

target_compile_features(anbpython PRIVATE cxx_std_17)
target_link_options(anbpython PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--no-undefined")
