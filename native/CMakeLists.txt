cmake_minimum_required(VERSION 3.10)

set(PLATFORM "" CACHE STRING "Target platform: rockchip, ascend, or software")

project(Native CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_AUTOMOC ON)

add_link_options(-Wl,--no-as-needed -Wl,--allow-shlib-undefined)

find_package(PkgConfig REQUIRED)

if(PLATFORM STREQUAL "rockchip")
    set(PLATFORM_LIBS librga.so librknnrt.so)
    set(PLATFORM_SRC rockchip.cpp)
elseif(PLATFORM STREQUAL "ascend")
    set(PLATFORM_LIBS ascendcl)
    set(PLATFORM_SRC ascend.cpp ascend-dec-client.cpp)

    add_executable(dec ascend-dec.cpp)
    target_link_libraries(dec ascendcl acl_dvpp Qt6::Core)
    install(TARGETS dec DESTINATION bin)
elseif(PLATFORM STREQUAL "software")
    set(PLATFORM_LIBS libMNN.so libMNN_Express.so libMNN_Vulkan.so vulkan)
    set(PLATFORM_SRC software.cpp)
else()
    message(FATAL_ERROR "Set PLATFORM to rockchip, ascend, or software")
endif()

find_package(Qt6 COMPONENTS Core Gui Network REQUIRED)

add_library(Native SHARED
    application.cpp
    draw.cpp
    http.cpp
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
