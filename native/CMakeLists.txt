cmake_minimum_required(VERSION 3.10)

set(PLATFORM "" CACHE STRING "Target platform: rk3588 or x64")

project(Native CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_AUTOMOC ON)

add_link_options(-Wl,--no-as-needed)

find_package(PkgConfig REQUIRED)
pkg_check_modules(V4L2 REQUIRED libv4l2 libv4lconvert)

if(PLATFORM STREQUAL "rk3588")
    set(PLATFORM_LIBS
        librga.so
        librknnrt.so
    )
    set(PLATFORM_SRC rk3588.cpp)
elseif(PLATFORM STREQUAL "x64")
    pkg_check_modules(FFMPEG REQUIRED libavcodec libavformat libavutil libswscale)
    set(PLATFORM_LIBS
        ${FFMPEG_LIBRARIES}
        libMNN.so libMNN_Express.so libMNN_Vulkan.so vulkan
    )
    set(PLATFORM_SRC x64.cpp)
else()
    message(FATAL_ERROR "Set PLATFORM to rk3588 or x64")
endif()

find_package(Qt6 COMPONENTS Core Gui Widgets Network REQUIRED)

add_library(Native SHARED
    application.cpp
    draw.cpp
    image.cpp
    inference.cpp
    output.cpp
    ${PLATFORM_SRC}
)

target_link_libraries(Native PRIVATE
    Qt6::Core Qt6::Gui Qt6::Widgets Qt6::Network
    ${V4L2_LIBRARIES}
    ${PLATFORM_LIBS}
)

install(TARGETS Native DESTINATION lib)
install(FILES native.h DESTINATION include)
