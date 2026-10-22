cmake_minimum_required(VERSION 3.10)
project(devicereset_native)

# Native PLT hook：拦截 __system_property_get
add_library(devicereset_native SHARED
    native-lib.cpp)

find_library(log-lib log)
target_link_libraries(devicereset_native ${log-lib})

# 体积优化：去掉异常/RTTI 依赖的额外开销
target_compile_options(devicereset_native PRIVATE -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(devicereset_native PRIVATE -Wl,--gc-sections)

# Android 15+/16KB 页设备兼容：ELF LOAD 段按 16KB 对齐
# （NDK r28 默认对 libc++_shared 等系统库同样 16KB 对齐，本库显式声明）
target_link_options(devicereset_native PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")
