/*
 * The JNI layer of the SuperDRO USB camera.
 *
 * This file is Apache License 2.0 code of SuperDRO. It uses libuvc (BSD) in the same library
 * and libusb (LGPL 2.1) as the separate shared library libusb1.0.so.
 *
 * The functions run in the process ":camera" only. The Kotlin object
 * org.superdro.platform.android.camera.UvcNative declares them. The camera service calls them on
 * one thread ("superdro-uvc-ops"). The render thread calls nativeAwaitFrame and nativeYuy2ToBitmap.
 *
 * Rules:
 * - One global context, with LIBUSB_OPTION_NO_DEVICE_DISCOVERY. The app gives the file
 *   descriptor of UsbDeviceConnection.getFileDescriptor(), and libuvc wraps it with
 *   libusb_wrap_sys_device (uvc_wrap).
 * - Each control transfer has a time limit (uvc_set_control_timeout). The negotiation stops at the
 *   first time-out.
 * - The start request has the selected frame interval. The negotiation tries it first.
 * - An init failure has the code -105, not the general code -99.
 */
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <errno.h>
#include <stdarg.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* libusb.h first: libuvc.h declares uvc_wrap only when LIBUSB_API_VERSION is known. */
#include <libusb.h>
#include "libuvc/libuvc.h"
#include "libuvc/libuvc_internal.h"

#define TAG "SuperDroUvc"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

/* The codec values of the mode list (UvcCodec in core:camera). */
#define CODEC_MJPEG 0
#define CODEC_H264 1
#define CODEC_UNCOMPRESSED 2
#define CODEC_UNKNOWN (-1)

/* The codes of CameraCodes in core:camera. */
#define CODE_ALREADY_STREAMING (-100)
#define CODE_NO_DESCRIPTOR (-101)
#define CODE_INIT_FAILED (-105)

/* The limits of the mode list and of the negotiation. */
#define MAX_MODES 512
#define MAX_CANDIDATES 24
#define FRAME_SLOTS 4
#define MIN_MJPEG_BYTES 1024
#define MIN_H264_BYTES_EXCLUSIVE 7

/* The values of the meta array of nativeAwaitFrame. */
#define META_WIDTH 0
#define META_HEIGHT 1
#define META_CODEC 2
#define META_TIME_NS 3
#define META_SEQUENCE 4
#define META_DROPPED 5
#define META_RUNTS 6
#define META_SIZE 7

typedef struct {
  uint8_t *data;
  size_t capacity;
  size_t length;
  int width;
  int height;
  int64_t time_ns;
  uint32_t sequence;
} frame_slot_t;

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static uvc_context_t *g_ctx = NULL;
static int g_init_done = 0;
static int g_init_error = 0;

static uvc_device_handle_t *g_devh = NULL;
static int g_fd = -1;
static int g_connection = -1;
static uvc_stream_handle_t *g_strmh = NULL;
static uvc_stream_ctrl_t g_ctrl;
static int g_codec = CODEC_UNKNOWN;
static int g_width = 0;
static int g_height = 0;

static pthread_mutex_t g_frame_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_frame_cond = PTHREAD_COND_INITIALIZER;
static frame_slot_t g_slots[FRAME_SLOTS];
static int g_slot_head = 0;
static int g_slot_count = 0;
static int g_streaming = 0;
static uint64_t g_frames = 0;
static uint64_t g_dropped = 0;
static uint64_t g_runts = 0;

/* ---------------------------------------------------------------------------------------- */
/* Helpers                                                                                   */
/* ---------------------------------------------------------------------------------------- */

static int is_h264_guid(const uint8_t *guid) {
  return guid[0] == 'H' && guid[1] == '2' && guid[2] == '6' && guid[3] == '4';
}

static int is_yuy2_fourcc(const uint8_t *guid) {
  return (guid[0] == 'Y' && guid[1] == 'U' && guid[2] == 'Y' && (guid[3] == '2' || guid[3] == 'V'));
}

/* The codec of a format descriptor (with UVC 1.5 H.264). */
static int codec_of(const uvc_format_desc_t *format) {
  switch (format->bDescriptorSubtype) {
    case UVC_VS_FORMAT_MJPEG:
      return CODEC_MJPEG;
    case UVC_VS_FORMAT_FRAME_BASED:
      return is_h264_guid(format->guidFormat) ? CODEC_H264 : CODEC_UNKNOWN;
    case UVC_VS_FORMAT_H264:
      return CODEC_H264;
    case UVC_VS_FORMAT_UNCOMPRESSED:
      return CODEC_UNCOMPRESSED;
    default:
      return CODEC_UNKNOWN;
  }
}

/* The FourCC of a format: the first 4 bytes of the GUID, high byte first. */
static int fourcc_of(const uvc_format_desc_t *format) {
  const uint8_t *g = format->guidFormat;
  return ((int) g[0] << 24) | ((int) g[1] << 16) | ((int) g[2] << 8) | (int) g[3];
}

static jstring error_text(JNIEnv *env, const char *format, ...) __attribute__((format(printf, 2, 3)));

static jstring error_text(JNIEnv *env, const char *format, ...) {
  char text[256];
  va_list args;
  va_start(args, format);
  vsnprintf(text, sizeof(text), format, args);
  va_end(args);
  LOGW("%s", text);
  return (*env)->NewStringUTF(env, text);
}

static void clear_slots_locked(void) {
  g_slot_head = 0;
  g_slot_count = 0;
}

static void free_slots(void) {
  pthread_mutex_lock(&g_frame_lock);
  for (int i = 0; i < FRAME_SLOTS; ++i) {
    free(g_slots[i].data);
    memset(&g_slots[i], 0, sizeof(g_slots[i]));
  }
  clear_slots_locked();
  pthread_mutex_unlock(&g_frame_lock);
}

/* Creates the global context one time. Call it with g_lock held. */
static int ensure_context_locked(int control_timeout_ms) {
  if (g_init_done) return g_ctx != NULL ? 0 : CODE_INIT_FAILED;
  g_init_done = 1;
  uvc_set_control_timeout((unsigned int) control_timeout_ms);
  libusb_set_option(NULL, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_WARNING);
  int result = libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
  if (result != LIBUSB_SUCCESS) {
    g_init_error = result;
    LOGW("no-discovery option rejected: %s (%d)", libusb_error_name(result), result);
    return CODE_INIT_FAILED;
  }
  uvc_error_t error = uvc_init(&g_ctx, NULL);
  if (error != UVC_SUCCESS) {
    g_init_error = error;
    g_ctx = NULL;
    LOGW("uvc_init failed: %s (%d)", uvc_strerror(error), error);
    return CODE_INIT_FAILED;
  }
  LOGI("context ready, control time limit %d ms", control_timeout_ms);
  return 0;
}

/* Opens the camera on fd. Call it with g_lock held. */
static int open_camera_locked(int fd, int connection) {
  if (fd < 0) return CODE_NO_DESCRIPTOR;
  if (g_ctx == NULL) return CODE_INIT_FAILED;
  if (g_devh != NULL && g_fd == fd && g_connection == connection) return 0;
  if (g_strmh != NULL) return CODE_ALREADY_STREAMING;
  if (g_devh != NULL) {
    uvc_close(g_devh);
    g_devh = NULL;
  }
  uvc_device_handle_t *devh = NULL;
  uvc_error_t error = uvc_wrap(fd, g_ctx, &devh);
  if (error != UVC_SUCCESS) {
    LOGW("open failed: %s (%d)", uvc_strerror(error), error);
    return error;
  }
  g_devh = devh;
  g_fd = fd;
  g_connection = connection;
  LOGI("camera opened (fd=%d, conn=%d, bcdUVC=%04x)", fd, connection, uvc_get_spec_version(devh));
  return 0;
}

/* ---------------------------------------------------------------------------------------- */
/* Frame callback                                                                            */
/* ---------------------------------------------------------------------------------------- */

static int accepts_frame(const uvc_frame_t *frame) {
  const uint8_t *data = (const uint8_t *) frame->data;
  switch (g_codec) {
    case CODEC_MJPEG:
      return frame->data_bytes >= MIN_MJPEG_BYTES && data[0] == 0xFF && data[1] == 0xD8;
    case CODEC_H264:
      return frame->data_bytes > MIN_H264_BYTES_EXCLUSIVE;
    case CODEC_UNCOMPRESSED:
      return frame->data_bytes >= (size_t) frame->width * (size_t) frame->height * 2u;
    default:
      return 0;
  }
}

static void frame_callback(uvc_frame_t *frame, void *user) {
  (void) user;
  pthread_mutex_lock(&g_frame_lock);
  if (!g_streaming) {
    pthread_mutex_unlock(&g_frame_lock);
    return;
  }
  if (!accepts_frame(frame)) {
    g_runts += 1;
    pthread_mutex_unlock(&g_frame_lock);
    return;
  }
  if (g_slot_count == FRAME_SLOTS) {
    /* The reader is slow: drop the oldest frame. */
    g_slot_head = (g_slot_head + 1) % FRAME_SLOTS;
    g_slot_count -= 1;
    g_dropped += 1;
  }
  frame_slot_t *slot = &g_slots[(g_slot_head + g_slot_count) % FRAME_SLOTS];
  if (slot->capacity < frame->data_bytes) {
    uint8_t *data = realloc(slot->data, frame->data_bytes);
    if (data == NULL) {
      g_dropped += 1;
      pthread_mutex_unlock(&g_frame_lock);
      return;
    }
    slot->data = data;
    slot->capacity = frame->data_bytes;
  }
  memcpy(slot->data, frame->data, frame->data_bytes);
  slot->length = frame->data_bytes;
  slot->width = (int) frame->width;
  slot->height = (int) frame->height;
  slot->time_ns = (int64_t) frame->capture_time_finished.tv_sec * 1000000000LL +
                  (int64_t) frame->capture_time_finished.tv_nsec;
  if (slot->time_ns <= 0) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    slot->time_ns = (int64_t) now.tv_sec * 1000000000LL + now.tv_nsec;
  }
  slot->sequence = frame->sequence;
  g_slot_count += 1;
  g_frames += 1;
  if (g_frames <= 3) {
    const uint8_t *data = (const uint8_t *) frame->data;
    LOGI("callback: codec=%d %ux%u bytes=%zu head=%02x %02x %02x %02x %02x", g_codec, frame->width, frame->height,
         frame->data_bytes, data[0], data[1], data[2], data[3], frame->data_bytes > 4 ? data[4] : 0);
  }
  pthread_cond_signal(&g_frame_cond);
  pthread_mutex_unlock(&g_frame_lock);
}

/* ---------------------------------------------------------------------------------------- */
/* Negotiation                                                                              */
/* ---------------------------------------------------------------------------------------- */

static int format_matches(const uvc_format_desc_t *format, int codec) {
  if (codec_of(format) != codec) return 0;
  if (codec == CODEC_UNCOMPRESSED) return is_yuy2_fourcc(format->guidFormat);
  return 1;
}

static uint32_t frame_default_interval(const uvc_frame_desc_t *frame) {
  if (frame->dwDefaultFrameInterval > 0) return frame->dwDefaultFrameInterval;
  if (frame->intervals != NULL && frame->intervals[0] > 0) return frame->intervals[0];
  return frame->dwMinFrameInterval;
}

static int contains(const uint32_t *values, int count, uint32_t value) {
  for (int i = 0; i < count; ++i) {
    if (values[i] == value) return 1;
  }
  return 0;
}

/* The candidate intervals: the selected interval first, then the intervals of the frame that are
 * nearest to it, then the default interval. Not more than MAX_CANDIDATES. */
static int candidate_intervals(const uvc_frame_desc_t *frame, uint32_t selected, uint32_t *out) {
  int count = 0;
  if (selected > 0) out[count++] = selected;
  if (frame->intervals != NULL) {
    uint32_t sorted[MAX_CANDIDATES * 4];
    int n = 0;
    for (const uint32_t *p = frame->intervals; *p && n < MAX_CANDIDATES * 4; ++p) sorted[n++] = *p;
    for (int i = 1; i < n; ++i) {
      uint32_t key = sorted[i];
      int64_t key_distance = llabs((int64_t) key - (int64_t) selected);
      int j = i - 1;
      while (j >= 0 && llabs((int64_t) sorted[j] - (int64_t) selected) > key_distance) {
        sorted[j + 1] = sorted[j];
        --j;
      }
      sorted[j + 1] = key;
    }
    for (int i = 0; i < n && count < MAX_CANDIDATES; ++i) {
      if (sorted[i] > 0 && !contains(out, count, sorted[i])) out[count++] = sorted[i];
    }
  } else if (frame->dwMinFrameInterval > 0) {
    /* A continuous range: the selected value must be in the range on a step. */
    uint32_t step = frame->dwFrameIntervalStep > 0 ? frame->dwFrameIntervalStep : 1;
    if (selected >= frame->dwMinFrameInterval && selected <= frame->dwMaxFrameInterval &&
        (selected - frame->dwMinFrameInterval) % step != 0) {
      uint32_t snapped = frame->dwMinFrameInterval + ((selected - frame->dwMinFrameInterval) / step) * step;
      if (!contains(out, count, snapped) && count < MAX_CANDIDATES) out[count++] = snapped;
    }
  }
  uint32_t fallback = frame_default_interval(frame);
  if (fallback > 0 && !contains(out, count, fallback) && count < MAX_CANDIDATES) out[count++] = fallback;
  return count;
}

/* One PROBE: GET_CUR (the device values, also the UVC 1.5 fields), our fields, SET_CUR, GET_CUR. */
static int probe(uvc_device_handle_t *devh, uvc_stream_ctrl_t *ctrl, const uvc_format_desc_t *format,
                 const uvc_frame_desc_t *frame, uint32_t interval, int interface_number) {
  memset(ctrl, 0, sizeof(*ctrl));
  ctrl->bInterfaceNumber = (uint8_t) interface_number;
  int result = uvc_query_stream_ctrl(devh, ctrl, 1, UVC_GET_CUR);
  if (result == UVC_ERROR_TIMEOUT) return result;
  ctrl->bmHint = 1; /* The frame interval is fixed. */
  ctrl->bFormatIndex = format->bFormatIndex;
  ctrl->bFrameIndex = frame->bFrameIndex;
  ctrl->dwFrameInterval = interval;
  ctrl->bInterfaceNumber = (uint8_t) interface_number;
  if (g_codec == CODEC_H264 && uvc_get_spec_version(devh) >= 0x0110) {
    /* The frame ID and the end of frame bits are required for H.264. */
    ctrl->bmFramingInfo |= 3;
  }
  result = uvc_query_stream_ctrl(devh, ctrl, 1, UVC_SET_CUR);
  if (result != UVC_SUCCESS) return result;
  result = uvc_query_stream_ctrl(devh, ctrl, 1, UVC_GET_CUR);
  if (result != UVC_SUCCESS) return result;
  if (ctrl->bFormatIndex != format->bFormatIndex || ctrl->bFrameIndex != frame->bFrameIndex) {
    return UVC_ERROR_INVALID_MODE;
  }
  if (g_codec == CODEC_H264 && uvc_get_spec_version(devh) >= 0x0110) ctrl->bmFramingInfo |= 3;
  return UVC_SUCCESS;
}

/* ---------------------------------------------------------------------------------------- */
/* JNI functions                                                                             */
/* ---------------------------------------------------------------------------------------- */

JNIEXPORT jint JNICALL
Java_org_superdro_platform_android_camera_UvcNative_nativeInit(JNIEnv *env, jclass type, jint control_timeout_ms) {
  (void) env;
  (void) type;
  pthread_mutex_lock(&g_lock);
  int result = ensure_context_locked(control_timeout_ms);
  pthread_mutex_unlock(&g_lock);
  return result;
}

JNIEXPORT jstring JNICALL
Java_org_superdro_platform_android_camera_UvcNative_nativeVersions(JNIEnv *env, jclass type) {
  (void) type;
  const struct libusb_version *usb = libusb_get_version();
  char text[96];
  snprintf(text, sizeof(text), "libusb %u.%u.%u.%u / libuvc %s", usb->major, usb->minor, usb->micro, usb->nano,
           LIBUVC_VERSION_STR);
  return (*env)->NewStringUTF(env, text);
}

/* Gives the mode list: [status, then codec, width, height, interval, FourCC for each mode].
 * SuperDRO gives one mode for each interval. */
JNIEXPORT jintArray JNICALL
Java_org_superdro_platform_android_camera_UvcNative_nativeListModes(JNIEnv *env, jclass type, jint fd, jint connection) {
  (void) type;
  jint *values = calloc(1 + MAX_MODES * 5, sizeof(jint));
  if (values == NULL) return NULL;
  int count = 0;
  pthread_mutex_lock(&g_lock);
  int status = g_init_done ? (g_ctx != NULL ? 0 : CODE_INIT_FAILED) : CODE_INIT_FAILED;
  if (status == 0) status = open_camera_locked(fd, connection);
  if (status == 0) {
    uvc_streaming_interface_t *stream_if;
    DL_FOREACH(g_devh->info->stream_ifs, stream_if) {
      uvc_format_desc_t *format;
      DL_FOREACH(stream_if->format_descs, format) {
        int codec = codec_of(format);
        int fourcc = fourcc_of(format);
        uvc_frame_desc_t *frame;
        DL_FOREACH(format->frame_descs, frame) {
          uint32_t intervals[MAX_CANDIDATES * 4];
          int n = 0;
          if (frame->intervals != NULL) {
            for (const uint32_t *p = frame->intervals; *p && n < MAX_CANDIDATES * 4; ++p) {
              if (!contains(intervals, n, *p)) intervals[n++] = *p;
            }
          } else {
            uint32_t range[3] = {frame->dwMinFrameInterval, frame->dwDefaultFrameInterval, frame->dwMaxFrameInterval};
            for (int i = 0; i < 3; ++i) {
              if (range[i] > 0 && !contains(intervals, n, range[i])) intervals[n++] = range[i];
            }
          }
          if (n == 0) intervals[n++] = frame->dwDefaultFrameInterval;
          for (int i = 0; i < n && count < MAX_MODES; ++i) {
            jint *entry = &values[1 + count * 5];
            entry[0] = codec;
            entry[1] = frame->wWidth;
            entry[2] = frame->wHeight;
            entry[3] = (jint) intervals[i];
            entry[4] = fourcc;
            count += 1;
          }
        }
      }
    }
    LOGI("list modes: %d entries", count);
  }
  pthread_mutex_unlock(&g_lock);
  values[0] = status;
  int length = status == 0 ? 1 + count * 5 : 1;
  jintArray result = (*env)->NewIntArray(env, length);
  if (result != NULL) (*env)->SetIntArrayRegion(env, result, 0, length, values);
  free(values);
  return result;
}

/* Negotiates and starts the stream. Gives null on success, else an error text that ends with
 * "(code)". */
JNIEXPORT jstring JNICALL
Java_org_superdro_platform_android_camera_UvcNative_nativeStartStream(JNIEnv *env, jclass type, jint fd,
                                                                     jint connection, jint codec, jint width,
                                                                     jint height, jint interval) {
  (void) type;
  pthread_mutex_lock(&g_lock);
  if (!g_init_done || g_ctx == NULL) {
    pthread_mutex_unlock(&g_lock);
    return error_text(env, "context init failed (%d)", CODE_INIT_FAILED);
  }
  if (fd < 0) {
    pthread_mutex_unlock(&g_lock);
    return error_text(env, "no usable file descriptor (%d)", CODE_NO_DESCRIPTOR);
  }
  if (g_strmh != NULL) {
    pthread_mutex_unlock(&g_lock);
    return error_text(env, "already streaming (%d)", CODE_ALREADY_STREAMING);
  }
  int opened = open_camera_locked(fd, connection);
  if (opened != 0) {
    pthread_mutex_unlock(&g_lock);
    return error_text(env, "open failed: %s (%d)", uvc_strerror(opened), opened);
  }

  /* The first format of the codec, and the frame of the size or of the nearest pixel count. */
  uvc_format_desc_t *found_format = NULL;
  uvc_frame_desc_t *found_frame = NULL;
  int interface_number = -1;
  int64_t best = INT64_MAX;
  uvc_streaming_interface_t *stream_if;
  DL_FOREACH(g_devh->info->stream_ifs, stream_if) {
    uvc_format_desc_t *format;
    DL_FOREACH(stream_if->format_descs, format) {
      if (!format_matches(format, codec)) continue;
      if (found_format != NULL && found_format != format) continue;
      uvc_frame_desc_t *frame;
      DL_FOREACH(format->frame_descs, frame) {
        int64_t distance = llabs((int64_t) frame->wWidth * frame->wHeight - (int64_t) width * height);
        if (distance < best) {
          best = distance;
          found_format = format;
          found_frame = frame;
          interface_number = stream_if->bInterfaceNumber;
        }
      }
    }
  }
  if (found_format == NULL || found_frame == NULL) {
    pthread_mutex_unlock(&g_lock);
    const char *name = codec == CODEC_MJPEG ? "MJPEG" : codec == CODEC_H264 ? "H.264" : "uncompressed";
    return error_text(env, "camera offers no %s format", name);
  }

  g_codec = codec;
  g_width = found_frame->wWidth;
  g_height = found_frame->wHeight;
  uint32_t candidates[MAX_CANDIDATES];
  int candidate_count = candidate_intervals(found_frame, (uint32_t) interval, candidates);
  uvc_error_t last = UVC_ERROR_INVALID_MODE;
  int negotiated = 0;
  uvc_claim_if(g_devh, interface_number);
  for (int i = 0; i < candidate_count; ++i) {
    last = probe(g_devh, &g_ctrl, found_format, found_frame, candidates[i], interface_number);
    if (last == UVC_SUCCESS) {
      negotiated = 1;
      break;
    }
    if (last == UVC_ERROR_TIMEOUT) {
      LOGW("control transfer timed out at interval %u", candidates[i]);
      break;
    }
  }
  if (!negotiated) {
    pthread_mutex_unlock(&g_lock);
    return error_text(env, "negotiation failed: %s (%d)", uvc_strerror(last), last);
  }
  uint64_t ceiling = (uint64_t) g_width * (uint64_t) g_height * 4u + 65536u;
  if (g_ctrl.dwMaxVideoFrameSize == 0 || g_ctrl.dwMaxVideoFrameSize > ceiling) {
    pthread_mutex_unlock(&g_lock);
    return error_text(env, "implausible dwMaxVideoFrameSize %u for %ux%u (ceiling %llu) (%d)",
                      g_ctrl.dwMaxVideoFrameSize, g_width, g_height, (unsigned long long) ceiling,
                      UVC_ERROR_INVALID_MODE);
  }
  LOGI("negotiated %dx%d at interval %u (asked %d, %d candidates)", g_width, g_height, g_ctrl.dwFrameInterval,
       interval, candidate_count);

  pthread_mutex_lock(&g_frame_lock);
  clear_slots_locked();
  g_frames = 0;
  g_dropped = 0;
  g_runts = 0;
  g_streaming = 1;
  pthread_mutex_unlock(&g_frame_lock);

  uvc_stream_handle_t *strmh = NULL;
  uvc_error_t error = uvc_stream_open_ctrl(g_devh, &strmh, &g_ctrl);
  if (error == UVC_SUCCESS) error = uvc_stream_start(strmh, frame_callback, NULL, 0);
  if (error != UVC_SUCCESS) {
    if (strmh != NULL) uvc_stream_close(strmh);
    pthread_mutex_lock(&g_frame_lock);
    g_streaming = 0;
    pthread_cond_broadcast(&g_frame_cond);
    pthread_mutex_unlock(&g_frame_lock);
    pthread_mutex_unlock(&g_lock);
    return error_text(env, "stream failed: %s (%d)", uvc_strerror(error), error);
  }
  g_strmh = strmh;
  LOGI("stream started: %dx%d interval=%u maxPayload=%u maxFrame=%u", g_width, g_height, g_ctrl.dwFrameInterval,
       g_ctrl.dwMaxPayloadTransferSize, g_ctrl.dwMaxVideoFrameSize);
  pthread_mutex_unlock(&g_lock);
  return NULL;
}

JNIEXPORT jint JNICALL
Java_org_superdro_platform_android_camera_UvcNative_nativeNegotiatedInterval(JNIEnv *env, jclass type) {
  (void) env;
  (void) type;
  pthread_mutex_lock(&g_lock);
  jint value = g_strmh != NULL ? (jint) g_ctrl.dwFrameInterval : 0;
  pthread_mutex_unlock(&g_lock);
  return value;
}

JNIEXPORT jint JNICALL
Java_org_superdro_platform_android_camera_UvcNative_nativeMaxFrameSize(JNIEnv *env, jclass type) {
  (void) env;
  (void) type;
  pthread_mutex_lock(&g_lock);
  jint value = (jint) g_ctrl.dwMaxVideoFrameSize;
  pthread_mutex_unlock(&g_lock);
  return value;
}

/* Copies the next frame into buffer. Gives the byte count, 0 on a time-out, -1 when the stream
 * stopped, -2 with no stream, and -3 when the buffer is too small (the frame is dropped). */
JNIEXPORT jint JNICALL
Java_org_superdro_platform_android_camera_UvcNative_nativeAwaitFrame(JNIEnv *env, jclass type, jbyteArray buffer,
                                                                    jlongArray meta, jint timeout_ms) {
  (void) type;
  pthread_mutex_lock(&g_frame_lock);
  if (!g_streaming && g_slot_count == 0) {
    int stopped = g_frames > 0 ? -1 : -2;
    pthread_mutex_unlock(&g_frame_lock);
    return stopped;
  }
  if (g_slot_count == 0) {
    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += timeout_ms / 1000;
    deadline.tv_nsec += (long) (timeout_ms % 1000) * 1000000L;
    if (deadline.tv_nsec >= 1000000000L) {
      deadline.tv_sec += 1;
      deadline.tv_nsec -= 1000000000L;
    }
    while (g_slot_count == 0 && g_streaming) {
      if (pthread_cond_timedwait(&g_frame_cond, &g_frame_lock, &deadline) == ETIMEDOUT) break;
    }
  }
  if (g_slot_count == 0) {
    int result = g_streaming ? 0 : -1;
    pthread_mutex_unlock(&g_frame_lock);
    return result;
  }
  frame_slot_t *slot = &g_slots[g_slot_head];
  g_slot_head = (g_slot_head + 1) % FRAME_SLOTS;
  g_slot_count -= 1;
  jsize capacity = (*env)->GetArrayLength(env, buffer);
  jint length;
  if ((size_t) capacity < slot->length) {
    g_dropped += 1;
    length = -3;
  } else {
    (*env)->SetByteArrayRegion(env, buffer, 0, (jsize) slot->length, (const jbyte *) slot->data);
    length = (jint) slot->length;
  }
  jlong values[META_SIZE];
  values[META_WIDTH] = slot->width;
  values[META_HEIGHT] = slot->height;
  values[META_CODEC] = g_codec;
  values[META_TIME_NS] = slot->time_ns;
  values[META_SEQUENCE] = slot->sequence;
  values[META_DROPPED] = (jlong) g_dropped;
  values[META_RUNTS] = (jlong) g_runts;
  pthread_mutex_unlock(&g_frame_lock);
  jsize meta_length = (*env)->GetArrayLength(env, meta);
  (*env)->SetLongArrayRegion(env, meta, 0, meta_length < META_SIZE ? meta_length : META_SIZE, values);
  return length;
}

/* Converts a YUY2 frame into an ARGB_8888 bitmap with the BT.601 video range. Gives 0, -1 for a
 * short frame, -2 for a bitmap of a different size, or -3 when the bitmap does not lock. */
JNIEXPORT jint JNICALL
Java_org_superdro_platform_android_camera_UvcNative_nativeYuy2ToBitmap(JNIEnv *env, jclass type, jbyteArray data,
                                                                      jint length, jint width, jint height,
                                                                      jobject bitmap) {
  (void) type;
  if (width <= 0 || height <= 0 || (int64_t) length < (int64_t) width * height * 2) return -1;
  AndroidBitmapInfo info;
  if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return -3;
  if ((int) info.width != width || (int) info.height != height || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
    return -2;
  }
  void *pixels = NULL;
  if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == NULL) return -3;
  jbyte *source = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
  if (source == NULL) {
    AndroidBitmap_unlockPixels(env, bitmap);
    return -3;
  }
  const uint8_t *in = (const uint8_t *) source;
  for (int y = 0; y < height; ++y) {
    const uint8_t *row = in + (size_t) y * (size_t) width * 2u;
    uint8_t *out = (uint8_t *) pixels + (size_t) y * info.stride;
    /* Each 4 bytes Y0 U Y1 V give two pixels. An odd last pixel stays black. */
    for (int x = 0; x + 1 < width; x += 2) {
      int u = row[x * 2 + 1] - 128;
      int v = row[x * 2 + 3] - 128;
      for (int k = 0; k < 2; ++k) {
        int c = (row[x * 2 + k * 2] - 16) * 298;
        int r = (c + 409 * v + 128) >> 8;
        int g = (c - 100 * u - 208 * v + 128) >> 8;
        int b = (c + 516 * u + 128) >> 8;
        uint8_t *pixel = out + (size_t) (x + k) * 4u;
        pixel[0] = (uint8_t) (r < 0 ? 0 : r > 255 ? 255 : r);
        pixel[1] = (uint8_t) (g < 0 ? 0 : g > 255 ? 255 : g);
        pixel[2] = (uint8_t) (b < 0 ? 0 : b > 255 ? 255 : b);
        pixel[3] = 255;
      }
    }
  }
  (*env)->ReleasePrimitiveArrayCritical(env, data, source, JNI_ABORT);
  AndroidBitmap_unlockPixels(env, bitmap);
  return 0;
}

static void stop_stream_locked(void) {
  pthread_mutex_lock(&g_frame_lock);
  g_streaming = 0;
  pthread_cond_broadcast(&g_frame_cond);
  pthread_mutex_unlock(&g_frame_lock);
  if (g_strmh != NULL) {
    uvc_stream_stop(g_strmh);
    uvc_stream_close(g_strmh);
    g_strmh = NULL;
    LOGI("stream stopped: %llu frames, %llu dropped, %llu runts", (unsigned long long) g_frames,
         (unsigned long long) g_dropped, (unsigned long long) g_runts);
  }
  pthread_mutex_lock(&g_frame_lock);
  clear_slots_locked();
  pthread_mutex_unlock(&g_frame_lock);
}

JNIEXPORT void JNICALL
Java_org_superdro_platform_android_camera_UvcNative_nativeStopStream(JNIEnv *env, jclass type) {
  (void) env;
  (void) type;
  pthread_mutex_lock(&g_lock);
  stop_stream_locked();
  pthread_mutex_unlock(&g_lock);
}

JNIEXPORT void JNICALL
Java_org_superdro_platform_android_camera_UvcNative_nativeCloseCamera(JNIEnv *env, jclass type) {
  (void) env;
  (void) type;
  pthread_mutex_lock(&g_lock);
  stop_stream_locked();
  if (g_devh != NULL) {
    uvc_close(g_devh);
    g_devh = NULL;
    LOGI("camera closed");
  }
  g_fd = -1;
  g_connection = -1;
  pthread_mutex_unlock(&g_lock);
  free_slots();
}
