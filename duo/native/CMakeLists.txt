# GameNativeDuo: builds only libvulkan_renderer.so (upstream app/src/main/cpp/CMakeLists.txt has stale targets). See duo/native/README.md.
cmake_minimum_required(VERSION 3.22.1)
project(GnduoVulkanRenderer C CXX)
set(SRC ${CMAKE_CURRENT_LIST_DIR}/../../app/src/main/cpp)
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "${CMAKE_SHARED_LINKER_FLAGS_RELEASE} -s")
add_subdirectory(${SRC}/lsfg-vk-android/thirdparty/dxbc dxbc EXCLUDE_FROM_ALL)
add_subdirectory(${SRC}/extras/adrenotools adrenotools)
foreach(s vert frag)
  add_custom_command(OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_${s}.h
    COMMAND glslangValidator -V --vn window_${s}_code -x -o ${CMAKE_CURRENT_BINARY_DIR}/window_${s}.h ${SRC}/winlator/window.${s}
    DEPENDS ${SRC}/winlator/window.${s})
endforeach()
add_library(vulkan_renderer SHARED
  ${SRC}/winlator/vulkan_jni.cpp ${SRC}/winlator/VulkanRendererContext.cpp ${SRC}/winlator/DuoSecondaryOutput.cpp ${SRC}/winlator/VulkanRendererScanout.cpp
  ${SRC}/winlator/VulkanRendererLsfg.cpp ${SRC}/lsfg/vk_dispatch.c ${SRC}/lsfg/lsfg_dll.c ${SRC}/lsfg/lsfg_dxbc.cpp
  ${SRC}/lsfg/lsfg_probe.c ${SRC}/lsfg/lsfg_jni.c ${SRC}/lsfg/lsfg_common.cpp ${SRC}/lsfg/lsfg_shaders.cpp
  ${SRC}/lsfg/lsfg_mipmaps.cpp ${SRC}/lsfg/lsfg_generate.cpp ${SRC}/lsfg/lsfg_alpha.cpp ${SRC}/lsfg/lsfg_beta.cpp
  ${SRC}/lsfg/lsfg_gamma.cpp ${SRC}/lsfg/lsfg_delta.cpp ${SRC}/lsfg/lsfg_chain.cpp ${SRC}/lsfg/lsfg_pacer.cpp
  ${SRC}/lsfg/vkr_lsfg.cpp ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h)
target_include_directories(vulkan_renderer PRIVATE ${CMAKE_CURRENT_BINARY_DIR} ${SRC}/winlator ${SRC}/lsfg)
set_target_properties(vulkan_renderer PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
target_compile_options(vulkan_renderer PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_libraries(vulkan_renderer log android vulkan adrenotools dxbc dl atomic)
target_link_options(vulkan_renderer PRIVATE -Wl,-z,max-page-size=16384)
