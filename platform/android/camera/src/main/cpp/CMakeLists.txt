# The native libraries of the USB camera.
#
# - libusb1.0.so: libusb 1.0.30 (LGPL 2.1), a separate shared library with no change, so that a
#   user can put a different build of libusb in its place (LGPL 2.1, section 6).
# - libsuperdro_uvc.so: the JNI layer of SuperDRO (superdro_uvc.c) and libuvc 0.0.8 (BSD) with
#   the changes of third_party/libuvc-0.0.8/SUPERDRO_CHANGES.txt. It links to libusb1.0.so.
#
# Both libraries have a 16 KB page alignment (max-page-size=16384) for the Android devices with
# 16 KB pages.
cmake_minimum_required(VERSION 3.22.1)
project(superdro_camera C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

set(LIBUSB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libusb-1.0.30)
set(LIBUVC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libuvc-0.0.8)

set(PAGE_ALIGNMENT "-Wl,-z,max-page-size=16384")

# libusb, with the files and the flags of android/jni/libusb.mk of the release.
add_library(usb1.0 SHARED
    ${LIBUSB_DIR}/libusb/core.c
    ${LIBUSB_DIR}/libusb/descriptor.c
    ${LIBUSB_DIR}/libusb/hotplug.c
    ${LIBUSB_DIR}/libusb/io.c
    ${LIBUSB_DIR}/libusb/sync.c
    ${LIBUSB_DIR}/libusb/strerror.c
    ${LIBUSB_DIR}/libusb/os/linux_usbfs.c
    ${LIBUSB_DIR}/libusb/os/events_posix.c
    ${LIBUSB_DIR}/libusb/os/threads_posix.c
    ${LIBUSB_DIR}/libusb/os/linux_netlink.c
)
target_include_directories(usb1.0
    PRIVATE ${LIBUSB_DIR}/android ${LIBUSB_DIR}/libusb ${LIBUSB_DIR}/libusb/os
    PUBLIC ${LIBUSB_DIR}/libusb
)
target_compile_options(usb1.0 PRIVATE -fvisibility=hidden -pthread -Wno-unused-parameter)
target_link_libraries(usb1.0 PRIVATE log)
target_link_options(usb1.0 PRIVATE ${PAGE_ALIGNMENT})

# libuvc (no libjpeg) and the JNI layer.
add_library(superdro_uvc SHARED
    superdro_uvc.c
    ${LIBUVC_DIR}/src/ctrl.c
    ${LIBUVC_DIR}/src/ctrl-gen.c
    ${LIBUVC_DIR}/src/device.c
    ${LIBUVC_DIR}/src/diag.c
    ${LIBUVC_DIR}/src/frame.c
    ${LIBUVC_DIR}/src/init.c
    ${LIBUVC_DIR}/src/misc.c
    ${LIBUVC_DIR}/src/stream.c
)
target_include_directories(superdro_uvc PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/config
    ${LIBUVC_DIR}/include
)
target_compile_options(superdro_uvc PRIVATE -fvisibility=hidden -Wall -Wno-unused-function)
target_link_libraries(superdro_uvc PRIVATE usb1.0 jnigraphics log)
target_link_options(superdro_uvc PRIVATE ${PAGE_ALIGNMENT})
