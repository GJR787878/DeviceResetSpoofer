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
