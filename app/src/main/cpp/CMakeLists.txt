cmake_minimum_required(VERSION 3.22.1)

project("ansylab")

# 第二阶段用到了 <atomic> / <vector> 的现代用法，固定到 C++17。
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# 关掉默认的 hidden 可见性：ansylab_* 这些纯 C 导出要让 Python 的 ctypes 能 dlsym 到。
# 源文件里对每个导出也加了 visibility("default")，这里是双保险。
set(CMAKE_CXX_VISIBILITY_PRESET default)
set(CMAKE_C_VISIBILITY_PRESET default)
set(CMAKE_VISIBILITY_INLINES_HIDDEN OFF)

add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp)

target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log)
