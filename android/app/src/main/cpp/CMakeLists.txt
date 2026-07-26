cmake_minimum_required(VERSION 3.22.1)
project("tun_engine")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O2 -Wall -Werror -Wno-unused-parameter")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--no-undefined -Wl,-z,relro -Wl,-z,now")

add_library(
    tun_engine
    SHARED
    tun_engine.cpp
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    tun_engine
    ${log-lib}
    ${android-lib}
)

target_include_directories(
    tun_engine
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)
