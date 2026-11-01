cmake_minimum_required(VERSION 3.22.1)
project(sega_native C)
set(CMAKE_C_STANDARD 11)
set(GENESIS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/genesis-plus-gx)
include(${CMAKE_CURRENT_SOURCE_DIR}/genesis_sources.cmake)
add_library(genesis_core OBJECT ${GENESIS_SOURCES})
target_include_directories(genesis_core PRIVATE
  ${GENESIS_DIR}/core ${GENESIS_DIR}/core/z80 ${GENESIS_DIR}/core/m68k
  ${GENESIS_DIR}/core/ntsc ${GENESIS_DIR}/core/sound ${GENESIS_DIR}/core/sound/minimp3
  ${GENESIS_DIR}/core/input_hw ${GENESIS_DIR}/core/cd_hw ${GENESIS_DIR}/core/cart_hw
  ${GENESIS_DIR}/core/cart_hw/svp ${GENESIS_DIR}/libretro
  ${GENESIS_DIR}/libretro/libretro-common/include)
target_compile_definitions(genesis_core PRIVATE
  __LIBRETRO__ USE_LIBRETRO_VFS USE_LIBTREMOR USE_16BPP_RENDERING FRONTEND_SUPPORTS_RGB565
  LSB_FIRST BYTE_ORDER=LITTLE_ENDIAN HAVE_ZLIB HAVE_YM3438_CORE HAVE_OPLL_CORE
  USE_PER_SOUND_CHANNELS_CONFIG M68K_OVERCLOCK_SHIFT=20 Z80_OVERCLOCK_SHIFT=20
  MAXROMSIZE=16777216 GIT_VERSION="f27655b5"
  # Match upstream Android.mk. The two pointer-alignment checks in vdp_render.c
  # use uintptr_t (patched) so they remain correct on 64-bit targets.
  ALIGN_LONG ALIGN_WORD)
target_compile_definitions(genesis_core PRIVATE "INLINE=static inline")
target_compile_options(genesis_core PRIVATE -O3 -DNDEBUG -fPIC -fno-strict-aliasing -ffunction-sections -fdata-sections -Wno-error)
if(ANDROID)
  add_library(sega_jni SHARED sega_jni.c $<TARGET_OBJECTS:genesis_core>)
  target_include_directories(sega_jni PRIVATE ${GENESIS_DIR}/libretro/libretro-common/include)
  target_compile_options(sega_jni PRIVATE -O3 -DNDEBUG -ffunction-sections -fdata-sections)
  target_link_options(sega_jni PRIVATE "-Wl,--gc-sections" "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/sega_exports.map")
  target_link_libraries(sega_jni android log)
else()
  add_library(genesis_host SHARED $<TARGET_OBJECTS:genesis_core>)
  target_link_options(genesis_host PRIVATE "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/sega_exports.map")
  target_link_libraries(genesis_host m)
endif()
if(SEGA_TEST_API)
  target_compile_definitions(sega_jni PRIVATE SEGA_TEST_API=1)
endif()
