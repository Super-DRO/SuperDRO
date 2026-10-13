/*
 * The libuvc configuration of SuperDRO. CMake makes this file from
 * libuvc_config.h.in in the libuvc build. SuperDRO does not use the libuvc
 * build, so this file has the values of libuvc 0.0.8 with no libjpeg.
 */
#ifndef LIBUVC_CONFIG_H
#define LIBUVC_CONFIG_H

#define LIBUVC_VERSION_MAJOR 0
#define LIBUVC_VERSION_MINOR 0
#define LIBUVC_VERSION_PATCH 8
#define LIBUVC_VERSION_STR "0.0.8"
#define LIBUVC_VERSION_INT                      \
  ((0 << 16) |                                  \
   (0 << 8) |                                   \
   (8))

#define LIBUVC_VERSION_GTE(major, minor, patch)                         \
  (LIBUVC_VERSION_INT >= (((major) << 16) | ((minor) << 8) | (patch)))

/* No libjpeg: SuperDRO decodes MJPEG with BitmapFactory in Kotlin. */
#undef LIBUVC_HAS_JPEG

#endif /* LIBUVC_CONFIG_H */
