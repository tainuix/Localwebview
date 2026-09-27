cmake_minimum_required(VERSION 3.22.1)

project("localserver_native")

# node.h 等標頭檔位置（由 GitHub Actions 或手動下載後放在 app/libnode/include/）
include_directories(${CMAKE_SOURCE_DIR}/../../../libnode/include/node)

# 匯入預先編譯好的 libnode.so（依 ANDROID_ABI 對應不同架構的檔案）
add_library(nodejs-lib SHARED IMPORTED)
set_target_properties(nodejs-lib PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../../../libnode/bin/${ANDROID_ABI}/libnode.so)

# 我們自己寫的 JNI 橋接程式
add_library(native-lib SHARED native-lib.cpp)

find_library(log-lib log)

target_link_libraries(native-lib nodejs-lib ${log-lib})
