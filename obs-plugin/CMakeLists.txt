cmake_minimum_required(VERSION 3.28...3.30)

# ------------------------------------------------
# OBS bootstrap
# ------------------------------------------------
include("${CMAKE_CURRENT_SOURCE_DIR}/cmake/common/bootstrap.cmake" NO_POLICY_SCOPE)

project(${_name} VERSION ${_version})

file(READ "${CMAKE_CURRENT_SOURCE_DIR}/buildspec.json" buildspec)
string(JSON obs_studio_version GET ${buildspec} dependencies obs-studio version)
list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/.deps/obs-studio-${obs_studio_version}/cmake/finders")

option(ENABLE_FRONTEND_API "Use obs-frontend-api for UI functionality" OFF)
option(ENABLE_QT "Use Qt functionality" OFF)

include(compilerconfig)
include(defaults)
include(helpers)

# ------------------------------------------------
# Plugin target
# ------------------------------------------------
add_library(${CMAKE_PROJECT_NAME} MODULE)

# ------------------------------------------------
# Core OBS dependencies
# ------------------------------------------------
find_package(libobs REQUIRED)
find_package(Threads REQUIRED)

# ------------------------------------------------
# FFmpeg (OBS-compatible, cross-platform)
# ------------------------------------------------
if(WIN32)
  # OBS provides FindFFmpeg.cmake (MODULE mode)
  find_package(FFmpeg REQUIRED MODULE)
else()
  find_package(PkgConfig REQUIRED)

  pkg_check_modules(AVCODEC REQUIRED libavcodec)
  pkg_check_modules(AVUTIL REQUIRED libavutil)
  pkg_check_modules(AVFORMAT REQUIRED libavformat)
  pkg_check_modules(AVFILTER REQUIRED libavfilter)
  pkg_check_modules(SWSCALE REQUIRED libswscale)

  target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${AVCODEC_INCLUDE_DIRS}
    ${AVUTIL_INCLUDE_DIRS}
    ${AVFORMAT_INCLUDE_DIRS}
    ${AVFILTER_INCLUDE_DIRS}
    ${SWSCALE_INCLUDE_DIRS}
  )
endif()

# ------------------------------------------------
# Link libraries
# ------------------------------------------------
target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE
  OBS::libobs
  Threads::Threads
)

if(WIN32)
  target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE
    FFmpeg::avcodec
    FFmpeg::avutil
    FFmpeg::avformat
    FFmpeg::avfilter
    FFmpeg::swscale
  )
else()
  target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE
    ${AVCODEC_LIBRARIES}
    ${AVUTIL_LIBRARIES}
    ${AVFORMAT_LIBRARIES}
    ${AVFILTER_LIBRARIES}
    ${SWSCALE_LIBRARIES}
  )
endif()

# ------------------------------------------------
# Optional OBS Frontend API
# ------------------------------------------------
if(ENABLE_FRONTEND_API)
  find_package(obs-frontend-api REQUIRED)
  target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE OBS::obs-frontend-api)
endif()

# ------------------------------------------------
# Optional Qt
# ------------------------------------------------
if(ENABLE_QT)
  find_package(Qt6 COMPONENTS Core Widgets REQUIRED)
  target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE Qt6::Core Qt6::Widgets)

  set_target_properties(${CMAKE_PROJECT_NAME}
    PROPERTIES
      AUTOMOC ON
      AUTOUIC ON
      AUTORCC ON
  )
endif()

# ------------------------------------------------
# Sources
# ------------------------------------------------
target_sources(${CMAKE_PROJECT_NAME} PRIVATE
  src/obs-ocam-source.c
)

# ------------------------------------------------
# Plugin output name
# ------------------------------------------------
set_target_properties_plugin(
  ${CMAKE_PROJECT_NAME}
  PROPERTIES OUTPUT_NAME ${_name}
)
