cmake_minimum_required(VERSION 2.8)
project(CJDK)
set(CMAKE_BUILD_TYPE Debug)
SET(CMAKE_INSTALL_RPATH_USE_LINK_PATH true)

find_package(JNI REQUIRED)

include_directories(${JNI_INCLUDE_DIRS})

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++11")
set(SOURCE_FILES src/CarbonReader.cpp src/CarbonReader.h test/main.cpp src/CarbonRow.h src/CarbonRow.cpp src/CarbonWriter.h  src/CarbonWriter.cpp src/CarbonSchemaReader.h src/CarbonSchemaReader.cpp src/Schema.h src/Schema.cpp)

add_executable(CJDK ${SOURCE_FILES})
get_filename_component(JAVA_JVM_LIBRARY_DIR ${JAVA_JVM_LIBRARY} DIRECTORY)
message(${JAVA_JVM_LIBRARY_DIR})
target_link_libraries(CJDK ${JAVA_JVM_LIBRARY})

