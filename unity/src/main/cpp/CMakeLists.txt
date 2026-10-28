cmake_minimum_required(VERSION 3.22.1)
project(stormdroid_native_unity C)

# il2mono: libil2cpp.so for Unity's Android player, backed by StormDroid's ARM64 Mono
# (libmonobdwgc-2.0.so in the app's native libs, loaded at run time). gen/stubs.c holds the weak
# "UNIMPLEMENTED" default for every il2cpp_* API function; regenerate it with
# il2mono/gen_stubs.py from the IL2CPP sources of a Unity Gradle export when the Unity version changes.
add_library(il2cpp SHARED
    il2mono/core.c
    il2mono/api_init.c
    il2mono/api_runtime.c
    il2mono/api_more.c
    il2mono/pad.c
    il2mono/config.c
    il2mono/bepinex.c
    il2mono/gen/stubs.c)
target_compile_options(il2cpp PRIVATE -std=c11 -fvisibility=hidden -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(il2cpp log dl)
target_link_options(il2cpp PRIVATE -Wl,--no-undefined)

# Unity's Swappy wrapper (frame pacing), loaded by libunity when present. Its source comes with Unity's
# Android Support package and is copied into unity/prepared/swappy by the preparation script; built as
# Unity's own CMake file for it does.
# UNITY_PREPARED: the prepared set unity/build.gradle packages (unityPrepared), unity/prepared by default.
if(NOT UNITY_PREPARED)
    set(UNITY_PREPARED ${CMAKE_CURRENT_SOURCE_DIR}/../../../prepared)
endif()
set(SWAPPY_SRC ${UNITY_PREPARED}/swappy)
if(EXISTS ${SWAPPY_SRC}/UnitySwappyWrapper.cpp)
    enable_language(CXX)
    find_package(games-frame-pacing REQUIRED CONFIG)
    # Unity's entry point is renamed and fronted by swappy_switch.cpp (a STORMDROID_NO_SWAPPY test switch).
    set_source_files_properties(${SWAPPY_SRC}/UnitySwappyWrapper.cpp PROPERTIES
        COMPILE_DEFINITIONS "UnitySwappyWrapperInit=UnitySwappyWrapperInitUnity")
    add_library(swappywrapper SHARED ${SWAPPY_SRC}/UnitySwappyWrapper.cpp swappy_switch.cpp)
    target_compile_definitions(swappywrapper PRIVATE EXTERNAL_FRAME_PACING_CODE)
    target_include_directories(swappywrapper PRIVATE ${SWAPPY_SRC})
    target_link_options(swappywrapper PRIVATE -Wl,--wrap=__android_log_print)
    target_link_libraries(swappywrapper android games-frame-pacing::swappy_static log)
endif()

# libvkshim.so: an observing Vulkan shim. libunity's dlopen("libvulkan.so") is patched to this name
# (tools/unity/patch_libunity.py); it depends on the system libvulkan.so, defines the resolvers and the
# frame-delivery calls, and logs them (tag VKSHIM) without changing anything.
# With STORMDROID_VULKAN_DRIVER it runs the engine on another Vulkan driver through a private copy of the
# system loader (vkshim/driver.c, libvkdriverhook.so; linker namespaces from liblinkernsbypass).
enable_language(CXX)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/liblinkernsbypass
                 ${CMAKE_CURRENT_BINARY_DIR}/linkernsbypass)
# On GPUs without BC it also creates the game's BC textures as ETC2/EAC and re-encodes their blocks
# (vkshim/bc: bcconv.c, bctranscode.cpp with bcdec (MIT / Unlicense) and etcpak (BSD-3)). Always -O2 there:
# the block encoder runs while the game loads.
add_library(vkshim SHARED vkshim/vkshim.c vkshim/driver.c
    vkshim/bc/bcconv.c
    vkshim/bc/bctranscode.cpp
    vkshim/bc/etcpak/ProcessRGB.cpp
    vkshim/bc/etcpak/Tables.cpp
    vkshim/bc/etcpak/Dither.cpp)
target_compile_options(vkshim PRIVATE -fvisibility=hidden -Wall -Wextra -Wno-unused-parameter
    $<$<COMPILE_LANGUAGE:C>:-std=c11>)
set_source_files_properties(vkshim/bc/bctranscode.cpp PROPERTIES COMPILE_OPTIONS "-O2;-fno-exceptions;-fno-rtti")
set_source_files_properties(vkshim/bc/etcpak/ProcessRGB.cpp vkshim/bc/etcpak/Tables.cpp vkshim/bc/etcpak/Dither.cpp
    PROPERTIES COMPILE_OPTIONS "-O2;-fno-exceptions;-fno-rtti;-w")
set_source_files_properties(vkshim/bc/bcconv.c PROPERTIES COMPILE_OPTIONS "-O2")
target_link_libraries(vkshim vulkan log dl linkernsbypass)
set_target_properties(vkshim PROPERTIES LINKER_LANGUAGE CXX)

add_library(vkdriverhook SHARED vkshim/driver_hook.c)
target_compile_options(vkdriverhook PRIVATE -fvisibility=hidden -Wall -Wextra)
target_link_options(vkdriverhook PRIVATE "-Wl,-z,global")

# libsteam_api64.so: "Steam is not running" for the game's Steamworks.NET (Windows-build name), which
# otherwise quits the game when the library is missing.
add_library(steam_api64_stub SHARED steam_stub/steam_api64_stub.c)
set_target_properties(steam_api64_stub PROPERTIES OUTPUT_NAME "steam_api64")
