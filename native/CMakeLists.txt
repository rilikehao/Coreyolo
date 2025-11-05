cmake_minimum_required(VERSION 3.10)

set(PLATFORM "" CACHE STRING "Target platform: aarch64 or x86_64")

project(Native CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_AUTOMOC ON)

add_link_options(-Wl,--no-as-needed)

find_package(PkgConfig REQUIRED)

if(PLATFORM STREQUAL "aarch64")
    set(PLATFORM_LIBS librga.so librknnrt.so)
    set(PLATFORM_SRC aarch64.cpp)
elseif(PLATFORM STREQUAL "x86_64")
    set(PLATFORM_LIBS libMNN.so libMNN_Express.so libMNN_Vulkan.so vulkan)
    set(PLATFORM_SRC x86_64.cpp)
else()
    message(FATAL_ERROR "Set PLATFORM to aarch64 or x86_64")
endif()

find_package(Qt6 COMPONENTS Core Gui Network REQUIRED)

add_library(Native SHARED
    application.cpp
    draw.cpp
    image.cpp
    inference.cpp
    ${PLATFORM_SRC}
)

target_link_libraries(Native PRIVATE
    Qt6::Core Qt6::Gui Qt6::Network
    ${PLATFORM_LIBS}
)

install(TARGETS Native DESTINATION lib)
install(FILES native.h DESTINATION include)
