#include <jni.h>

#include <android/log.h>

#include <errno.h>
#include <fcntl.h>
#include <linux/videodev2.h>
#include <poll.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/system_properties.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

#define LOG_TAG "amlogic-v4l2-capture"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define AML_VIDIOC_SET_PORT_TYPE 0xC0045627UL
#define AML_VIDIOC_SET_FRAME_RATE 0xC0CC5616UL
#define AML_VIDIOC_SET_MODE 0xC004562FUL
#define AML_VIDIOC_SET_CTRL 0xC008561CUL
#define AML_VIDIOC_SET_CROP 0x4014563CUL
#define AML_VIDIOC_GET_FMT 0xC0CC5604UL

#define FRAME_INFO_PTS_US 0
#define FRAME_INFO_FLAGS 1
#define FRAME_INFO_INDEX 2
#define FRAME_FLAG_KEYFRAME 1
#define V4L2_EAGAIN_CODE (-11)
#define AML_DEFAULT_WIDTH 640
#define AML_DEFAULT_HEIGHT 480
#define AML_DEFAULT_PIXEL_FORMAT V4L2_PIX_FMT_NV21

typedef struct {
    void *address;
    size_t length;
} MmapBuffer;

typedef struct {
    int fd;
    uint32_t width;
    uint32_t height;
    uint32_t pixel_format;
    uint32_t bytes_per_line;
    uint32_t frame_capacity;
    MmapBuffer *buffers;
    uint32_t *refcounts;
    uint32_t buffer_count;
    bool streaming;
    bool microdimming_enabled;
    bool microdimming_warned;
} CaptureContext;

static pthread_mutex_t g_instance_mutex = PTHREAD_MUTEX_INITIALIZER;
static int g_open_instances = 0;

static void release_instance_slot(void) {
    pthread_mutex_lock(&g_instance_mutex);
    if (g_open_instances > 0) {
        --g_open_instances;
    }
    pthread_mutex_unlock(&g_instance_mutex);
}

static int xioctl(int fd, unsigned long request, void *arg) {
    int ret;
    do {
        ret = ioctl(fd, request, arg);
    } while (ret == -1 && errno == EINTR);
    return ret;
}

static void notify_state_callback(int state) {
    // Mirror HAL callback values in logs: start=0, pause=1, stop=3.
    LOGI("state_callback(%d)", state);
}

static void throw_io_exception(JNIEnv *env, const char *prefix) {
    char msg[256];
    if (errno != 0) {
        snprintf(msg, sizeof(msg), "%s: %s", prefix, strerror(errno));
    } else {
        snprintf(msg, sizeof(msg), "%s", prefix);
    }
    jclass cls = env->FindClass("java/io/IOException");
    if (cls) {
        env->ThrowNew(cls, msg);
    }
}

static void close_context(CaptureContext *ctx) {
    if (!ctx) {
        return;
    }

    if (ctx->streaming && ctx->fd >= 0) {
        enum v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        if (xioctl(ctx->fd, VIDIOC_STREAMOFF, &type) < 0) {
            LOGW("VIDIOC_STREAMOFF failed: %s", strerror(errno));
        }
        ctx->streaming = false;
        notify_state_callback(3);
    }

    if (ctx->buffers) {
        for (uint32_t i = 0; i < ctx->buffer_count; ++i) {
            if (ctx->buffers[i].address && ctx->buffers[i].length > 0) {
                if (munmap(ctx->buffers[i].address, ctx->buffers[i].length) < 0) {
                    LOGW("munmap(%u) failed: %s", i, strerror(errno));
                }
            }
        }
        free(ctx->buffers);
    }
    if (ctx->refcounts) {
        free(ctx->refcounts);
    }

    if (ctx->fd >= 0) {
        close(ctx->fd);
    }

    release_instance_slot();

    free(ctx);
}

static void amlogic_configure_port_type(CaptureContext *ctx, int port_type) {
    if (!ctx || ctx->fd < 0) {
        return;
    }
    uint32_t value = (uint32_t) port_type;
    if (xioctl(ctx->fd, AML_VIDIOC_SET_PORT_TYPE, &value) < 0) {
        LOGW("AML set port type 0x%x failed: %s", port_type, strerror(errno));
    } else {
        LOGI("AML port type set to 0x%x", port_type);
    }
}

static int amlogic_resolve_port_type(int source_type, int explicit_port_type) {
    if (explicit_port_type != -1) {
        return explicit_port_type;
    }

    char prop[PROP_VALUE_MAX];
    memset(prop, 0, sizeof(prop));
    __system_property_get("ro.vendor.screencontrol.porttype", prop);
    bool port_type_1 = strcmp(prop, "1") == 0;

    // Match ScreenManager selection behavior.
    if (source_type == 1) {
        return port_type_1 ? 0x1100A006 : 0x1100A000;
    }
    return port_type_1 ? 0x1100A002 : 0x1100A001;
}

static void amlogic_configure_mode(CaptureContext *ctx, int mode) {
    if (!ctx || ctx->fd < 0 || mode < 0) {
        return;
    }
    uint32_t value = (uint32_t) mode;
    if (xioctl(ctx->fd, AML_VIDIOC_SET_MODE, &value) < 0) {
        LOGW("AML set mode(%d) failed: %s", mode, strerror(errno));
    }
}

static void amlogic_configure_rotation(CaptureContext *ctx, int rotation) {
    if (!ctx || ctx->fd < 0) {
        return;
    }
    if (rotation < 0) {
        return;
    }
    if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
        LOGW("Ignore invalid rotation value: %d", rotation);
        return;
    }

    struct v4l2_control ctrl;
    memset(&ctrl, 0, sizeof(ctrl));
    ctrl.id = V4L2_CID_ROTATE;
    ctrl.value = rotation;
    if (xioctl(ctx->fd, AML_VIDIOC_SET_CTRL, &ctrl) < 0) {
        LOGW("AML set rotation(%d) failed: %s", rotation, strerror(errno));
    }
}

static void amlogic_configure_crop(CaptureContext *ctx, int left, int top, int width, int height) {
    if (!ctx || ctx->fd < 0 || width <= 0 || height <= 0 || left < 0 || top < 0) {
        return;
    }

    uint32_t right = (uint32_t) (left + width - 1);
    uint32_t bottom = (uint32_t) (top + height - 1);
    uint32_t crop[5];
    crop[0] = 3;
    crop[1] = (uint32_t) left;
    crop[2] = (uint32_t) top;
    crop[3] = right;
    crop[4] = bottom;
    if (xioctl(ctx->fd, AML_VIDIOC_SET_CROP, crop) < 0) {
        LOGW("AML set crop(%d,%d,%d,%d) failed: %s", left, top, width, height, strerror(errno));
    }
}

static void amlogic_query_current_source_size(CaptureContext *ctx, struct v4l2_format *fmt) {
    if (!ctx || !fmt || ctx->fd < 0) {
        return;
    }

    struct v4l2_format current_fmt;
    memset(&current_fmt, 0, sizeof(current_fmt));
    current_fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (xioctl(ctx->fd, AML_VIDIOC_GET_FMT, &current_fmt) < 0) {
        LOGW("VIDIOC_G_FMT failed: %s", strerror(errno));
        return;
    }

    if (current_fmt.fmt.pix.width > 0 && current_fmt.fmt.pix.height > 0) {
        fmt->fmt.pix.width = current_fmt.fmt.pix.width;
        fmt->fmt.pix.height = current_fmt.fmt.pix.height;
        if (current_fmt.fmt.pix.bytesperline > 0) {
            fmt->fmt.pix.bytesperline = current_fmt.fmt.pix.bytesperline;
        }
        if (current_fmt.fmt.pix.sizeimage > 0) {
            fmt->fmt.pix.sizeimage = current_fmt.fmt.pix.sizeimage;
        }
    }
}

static void amlogic_configure_frame_rate(CaptureContext *ctx, int fps) {
    if (!ctx || ctx->fd < 0 || fps <= 0) {
        return;
    }

    struct v4l2_streamparm parm;
    memset(&parm, 0, sizeof(parm));
    parm.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    parm.parm.capture.timeperframe.numerator = 1;
    parm.parm.capture.timeperframe.denominator = (uint32_t) fps;
    if (xioctl(ctx->fd, VIDIOC_S_PARM, &parm) < 0) {
        LOGW("VIDIOC_S_PARM(%d) failed: %s", fps, strerror(errno));
    }

    uint32_t aml_data[51];
    memset(aml_data, 0, sizeof(aml_data));
    aml_data[0] = 1;
    aml_data[3] = 1;
    aml_data[4] = (uint32_t) fps;
    if (xioctl(ctx->fd, AML_VIDIOC_SET_FRAME_RATE, aml_data) < 0) {
        LOGW("AML set frame rate(%d) failed: %s", fps, strerror(errno));
    }
}

static bool is_capture_streaming_supported(int fd) {
    struct v4l2_capability cap;
    memset(&cap, 0, sizeof(cap));
    if (xioctl(fd, VIDIOC_QUERYCAP, &cap) < 0) {
        LOGW("VIDIOC_QUERYCAP failed: %s, continue in compatibility mode", strerror(errno));
        return true;
    }

    uint32_t caps = cap.capabilities;
    if (caps & V4L2_CAP_DEVICE_CAPS) {
        caps = cap.device_caps;
    }

    bool capture = (caps & V4L2_CAP_VIDEO_CAPTURE) != 0;
    bool streaming = (caps & V4L2_CAP_STREAMING) != 0;
    if (!capture || !streaming) {
        LOGW("V4L2 capability mismatch (capture=%d, streaming=%d), continue in compatibility mode", capture ? 1 : 0, streaming ? 1 : 0);
    }
    return true;
}

static bool try_set_format(int fd, int width, int height, uint32_t pixel_format, struct v4l2_format *out_fmt) {
    struct v4l2_format fmt;
    memset(&fmt, 0, sizeof(fmt));
    fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    fmt.fmt.pix.width = (uint32_t) width;
    fmt.fmt.pix.height = (uint32_t) height;
    fmt.fmt.pix.pixelformat = pixel_format;
    fmt.fmt.pix.field = V4L2_FIELD_ANY;
    if (xioctl(fd, VIDIOC_S_FMT, &fmt) < 0) {
        return false;
    }
    *out_fmt = fmt;
    return true;
}

static bool is_supported_pixel_format(uint32_t pixel_format) {
    switch (pixel_format) {
        case V4L2_PIX_FMT_NV21:
        case V4L2_PIX_FMT_NV12:
        case V4L2_PIX_FMT_YVU420:
        case V4L2_PIX_FMT_YUYV:
        case V4L2_PIX_FMT_RGB24:
        case V4L2_PIX_FMT_RGB32:
        case V4L2_PIX_FMT_RGB565:
        case V4L2_PIX_FMT_RGB555:
            return true;
        default:
            return false;
    }
}

static bool is_yuv420_family(uint32_t pixel_format) {
    switch (pixel_format) {
        case V4L2_PIX_FMT_NV12:
        case V4L2_PIX_FMT_NV21:
        case V4L2_PIX_FMT_YVU420:
            return true;
        default:
            return false;
    }
}

static void amlogic_apply_microdimming_yuv420(const uint8_t *src, uint8_t *dst, uint32_t width, uint32_t height, uint32_t bytes_per_line,
                                      uint32_t frame_size) {
    if (!src || !dst || width == 0 || height == 0) {
        return;
    }

    uint32_t stride = bytes_per_line > 0 ? bytes_per_line : width;
    if (stride < width) {
        stride = width;
    }

    uint32_t y_plane_size = stride * height;
    uint32_t uv_plane_size = stride * (height / 2);
    uint32_t min_size = y_plane_size + uv_plane_size;
    if (frame_size < min_size) {
        return;
    }

    memset(dst, 0, y_plane_size);

    const uint32_t block_w = 8;
    const uint32_t block_h = 8;
    for (uint32_t by = 0; by < height; by += block_h) {
        uint32_t bh = height - by;
        if (bh > block_h) {
            bh = block_h;
        }
        for (uint32_t bx = 0; bx < width; bx += block_w) {
            uint32_t bw = width - bx;
            if (bw > block_w) {
                bw = block_w;
            }

            uint32_t sum = 0;
            for (uint32_t y = 0; y < bh; ++y) {
                const uint8_t *src_row = src + (by + y) * stride + bx;
                for (uint32_t x = 0; x < bw; ++x) {
                    sum += src_row[x];
                }
            }
            uint8_t luma = (uint8_t) (sum / (bw * bh));
            for (uint32_t y = 0; y < bh; ++y) {
                uint8_t *dst_row = dst + (by + y) * stride + bx;
                memset(dst_row, luma, bw);
            }
        }
    }

    memset(dst + y_plane_size, 0x80, uv_plane_size);
    if (frame_size > min_size) {
        memset(dst + min_size, 0, frame_size - min_size);
    }
}

static uint32_t estimate_frame_size_for_format(uint32_t pixel_format, uint32_t width, uint32_t height, uint32_t bytes_per_line) {
    uint32_t bpl = bytes_per_line > 0 ? bytes_per_line : width;
    if (bpl == 0 || height == 0) {
        return 0;
    }

    switch (pixel_format) {
        case V4L2_PIX_FMT_NV12:
        case V4L2_PIX_FMT_NV21:
        case V4L2_PIX_FMT_YVU420:
            return bpl * height * 3 / 2;
        case V4L2_PIX_FMT_YUYV:
        case V4L2_PIX_FMT_RGB565:
        case V4L2_PIX_FMT_RGB555:
        case V4L2_PIX_FMT_RGB24:
        case V4L2_PIX_FMT_RGB32:
            return bpl * height;
        default:
            return width * height * 2;
    }
}

static bool set_format_with_candidates(int fd, int width, int height, uint32_t preferred_format, struct v4l2_format *out_fmt) {
    uint32_t candidates[] = {
            preferred_format,
            V4L2_PIX_FMT_NV21,
            V4L2_PIX_FMT_NV12,
            V4L2_PIX_FMT_YVU420,
            V4L2_PIX_FMT_YUYV,
            V4L2_PIX_FMT_RGB24,
            V4L2_PIX_FMT_RGB32,
            V4L2_PIX_FMT_RGB565,
            V4L2_PIX_FMT_RGB555,
    };

    bool has_candidate = false;
    for (size_t i = 0; i < sizeof(candidates) / sizeof(candidates[0]); ++i) {
        uint32_t candidate = candidates[i];
        if (!is_supported_pixel_format(candidate)) {
            continue;
        }

        bool duplicate = false;
        for (size_t j = 0; j < i; ++j) {
            if (candidates[j] == candidate) {
                duplicate = true;
                break;
            }
        }
        if (duplicate) {
            continue;
        }

        has_candidate = true;
        if (try_set_format(fd, width, height, candidate, out_fmt)) {
            return true;
        }
    }

    if (!has_candidate) {
        return try_set_format(fd, width, height, AML_DEFAULT_PIXEL_FORMAT, out_fmt);
    }

    return false;
}

static uint32_t estimate_frame_size(const CaptureContext *ctx) {
    if (!ctx) {
        return 0;
    }
    return estimate_frame_size_for_format(ctx->pixel_format, ctx->width, ctx->height, ctx->bytes_per_line);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_genymobile_scrcpy_video_AmlogicV4l2CaptureNative_nativeOpen(JNIEnv *env, jclass clazz,
                                                               jstring device_path, jint width,
                                                               jint height, jint fps, jint port_type,
                                                               jint source_type,
                                                               jint mode, jint rotation, jint crop_left,
                                                               jint crop_top, jint crop_width, jint crop_height,
                                                               jint req_buf_count, jint preferred_pixel_format,
                                                               jlongArray open_info) {
    (void) clazz;

    if (!device_path) {
        errno = EINVAL;
        throw_io_exception(env, "Device path is null");
        return 0;
    }

    const char *path = env->GetStringUTFChars(device_path, NULL);
    if (!path) {
        return 0;
    }

    int fd = open(path, O_RDWR | O_NONBLOCK);
    env->ReleaseStringUTFChars(device_path, path);

    if (fd < 0) {
        throw_io_exception(env, "Failed to open V4L2 device");
        return 0;
    }

    pthread_mutex_lock(&g_instance_mutex);
    if (g_open_instances >= 2) {
        pthread_mutex_unlock(&g_instance_mutex);
        close(fd);
        errno = EBUSY;
        throw_io_exception(env, "Amlogic V4L2 supports at most 2 opened instances");
        return 0;
    }
    ++g_open_instances;
    pthread_mutex_unlock(&g_instance_mutex);

    if (!is_capture_streaming_supported(fd)) {
        close(fd);
        release_instance_slot();
        errno = ENODEV;
        throw_io_exception(env, "VIDIOC_QUERYCAP failed or capture/streaming unsupported");
        return 0;
    }

    CaptureContext *ctx = (CaptureContext *) calloc(1, sizeof(CaptureContext));
    if (!ctx) {
        close(fd);
        release_instance_slot();
        errno = ENOMEM;
        throw_io_exception(env, "Failed to allocate capture context");
        return 0;
    }
    ctx->fd = fd;
    ctx->streaming = false;

    int resolved_port_type = amlogic_resolve_port_type(source_type, port_type);
    ctx->microdimming_enabled = ((uint32_t) resolved_port_type & 0x00010000U) != 0U;
    ctx->microdimming_warned = false;
    if (ctx->microdimming_enabled) {
        LOGI("Amlogic microdimming path enabled by portType bit16");
    }
    amlogic_configure_port_type(ctx, resolved_port_type);
    amlogic_configure_mode(ctx, mode);
    amlogic_configure_rotation(ctx, rotation);
    amlogic_configure_frame_rate(ctx, fps);

    int requested_width = width;
    int requested_height = height;
    uint32_t requested_format = (uint32_t) preferred_pixel_format;
    bool default_set_format = false;
    if (requested_width <= 0 || requested_height <= 0) {
        requested_width = AML_DEFAULT_WIDTH;
        requested_height = AML_DEFAULT_HEIGHT;
        requested_format = AML_DEFAULT_PIXEL_FORMAT;
        default_set_format = true;
        LOGW("Invalid set_format size(%d,%d), fallback to 640x480+NV21", width, height);
    }
    if (!is_supported_pixel_format(requested_format)) {
        requested_width = AML_DEFAULT_WIDTH;
        requested_height = AML_DEFAULT_HEIGHT;
        requested_format = AML_DEFAULT_PIXEL_FORMAT;
        default_set_format = true;
        LOGW("Unsupported set_format fourcc(0x%x), fallback to 640x480+NV21", (uint32_t) preferred_pixel_format);
    }

    struct v4l2_format fmt;
    memset(&fmt, 0, sizeof(fmt));
    if (!set_format_with_candidates(fd, requested_width, requested_height, requested_format, &fmt)) {
        if (!try_set_format(fd, AML_DEFAULT_WIDTH, AML_DEFAULT_HEIGHT, AML_DEFAULT_PIXEL_FORMAT, &fmt)) {
            close_context(ctx);
            throw_io_exception(env, "VIDIOC_S_FMT failed (including 640x480+NV21 fallback)");
            return 0;
        }
        default_set_format = true;
    }
    if (default_set_format) {
        LOGI("Applied default set_format fallback: %ux%u fourcc=0x%x", fmt.fmt.pix.width, fmt.fmt.pix.height, fmt.fmt.pix.pixelformat);
    }

    amlogic_configure_crop(ctx, crop_left, crop_top, crop_width, crop_height);

    struct v4l2_requestbuffers req;
    memset(&req, 0, sizeof(req));
    req.count = req_buf_count >= 2 ? (uint32_t) req_buf_count : 4;
    req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    req.memory = V4L2_MEMORY_MMAP;

    if (xioctl(fd, VIDIOC_REQBUFS, &req) < 0) {
        close_context(ctx);
        throw_io_exception(env, "VIDIOC_REQBUFS failed");
        return 0;
    }

    if (req.count < 2) {
        close_context(ctx);
        errno = ENOMEM;
        throw_io_exception(env, "Insufficient V4L2 buffers");
        return 0;
    }

    ctx->buffers = (MmapBuffer *) calloc(req.count, sizeof(MmapBuffer));
    if (!ctx->buffers) {
        close_context(ctx);
        errno = ENOMEM;
        throw_io_exception(env, "Failed to allocate mmap buffer table");
        return 0;
    }

    ctx->refcounts = (uint32_t *) calloc(req.count, sizeof(uint32_t));
    if (!ctx->refcounts) {
        close_context(ctx);
        errno = ENOMEM;
        throw_io_exception(env, "Failed to allocate refcount table");
        return 0;
    }

    ctx->buffer_count = req.count;
    for (uint32_t i = 0; i < req.count; ++i) {
        struct v4l2_buffer buf;
        memset(&buf, 0, sizeof(buf));
        buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        buf.memory = V4L2_MEMORY_MMAP;
        buf.index = i;

        if (xioctl(fd, VIDIOC_QUERYBUF, &buf) < 0) {
            close_context(ctx);
            throw_io_exception(env, "VIDIOC_QUERYBUF failed");
            return 0;
        }

        void *address = mmap(NULL, buf.length, PROT_READ | PROT_WRITE, MAP_SHARED, fd, (off_t) buf.m.offset);
        if (address == MAP_FAILED) {
            close_context(ctx);
            throw_io_exception(env, "mmap failed");
            return 0;
        }

        ctx->buffers[i].address = address;
        ctx->buffers[i].length = buf.length;
    }

    for (uint32_t i = 0; i < req.count; ++i) {
        struct v4l2_buffer buf;
        memset(&buf, 0, sizeof(buf));
        buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        buf.memory = V4L2_MEMORY_MMAP;
        buf.index = i;
        if (xioctl(fd, VIDIOC_QBUF, &buf) < 0) {
            close_context(ctx);
            throw_io_exception(env, "VIDIOC_QBUF failed");
            return 0;
        }
    }

    enum v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (xioctl(fd, VIDIOC_STREAMON, &type) < 0) {
        close_context(ctx);
        throw_io_exception(env, "VIDIOC_STREAMON failed");
        return 0;
    }

    ctx->streaming = true;
    notify_state_callback(0);
    ctx->width = fmt.fmt.pix.width;
    ctx->height = fmt.fmt.pix.height;
    ctx->pixel_format = fmt.fmt.pix.pixelformat;
    ctx->bytes_per_line = fmt.fmt.pix.bytesperline;
    ctx->frame_capacity = fmt.fmt.pix.sizeimage;
    if (ctx->bytes_per_line == 0) {
        ctx->bytes_per_line = ctx->width;
    }
    if (ctx->frame_capacity == 0) {
        ctx->frame_capacity = estimate_frame_size(ctx);
    }

    amlogic_query_current_source_size(ctx, &fmt);
    ctx->width = fmt.fmt.pix.width;
    ctx->height = fmt.fmt.pix.height;
    if (fmt.fmt.pix.bytesperline > 0) {
        ctx->bytes_per_line = fmt.fmt.pix.bytesperline;
    }
    if (fmt.fmt.pix.sizeimage > 0) {
        ctx->frame_capacity = fmt.fmt.pix.sizeimage;
    }
    if (ctx->bytes_per_line == 0) {
        ctx->bytes_per_line = ctx->width;
    }
    if (ctx->frame_capacity == 0) {
        ctx->frame_capacity = estimate_frame_size(ctx);
    }

    if (open_info && env->GetArrayLength(open_info) >= 5) {
        jlong values[5];
        values[0] = (jlong) ctx->width;
        values[1] = (jlong) ctx->height;
        values[2] = (jlong) ctx->frame_capacity;
        values[3] = (jlong) ctx->pixel_format;
        values[4] = (jlong) ctx->bytes_per_line;
        env->SetLongArrayRegion(open_info, 0, 5, values);
    }

    LOGI("Opened V4L2 %ux%u, fmt=0x%x, bytesPerLine=%u, frameCapacity=%u, buffers=%u",
         ctx->width, ctx->height, ctx->pixel_format, ctx->bytes_per_line, ctx->frame_capacity, ctx->buffer_count);
    return (jlong) (intptr_t) ctx;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_genymobile_scrcpy_video_AmlogicV4l2CaptureNative_nativeReadFrame(JNIEnv *env, jclass clazz,
                                                                    jlong handle, jobject frame_buffer,
                                                                    jlongArray frame_info) {
    (void) clazz;

    CaptureContext *ctx = (CaptureContext *) (intptr_t) handle;
    if (!ctx || ctx->fd < 0) {
        errno = EINVAL;
        throw_io_exception(env, "Invalid capture handle");
        return -1;
    }

    if (!frame_buffer) {
        errno = EINVAL;
        throw_io_exception(env, "Frame buffer is null");
        return -1;
    }

    uint8_t *dst = (uint8_t *) env->GetDirectBufferAddress(frame_buffer);
    jlong dst_capacity = env->GetDirectBufferCapacity(frame_buffer);
    if (!dst || dst_capacity <= 0) {
        errno = EINVAL;
        throw_io_exception(env, "Frame buffer must be a direct ByteBuffer");
        return -1;
    }

    struct v4l2_buffer buf;
    memset(&buf, 0, sizeof(buf));
    buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    buf.memory = V4L2_MEMORY_MMAP;

    if (xioctl(ctx->fd, VIDIOC_DQBUF, &buf) < 0) {
        if (errno == EAGAIN) {
            return V4L2_EAGAIN_CODE;
        }
        throw_io_exception(env, "VIDIOC_DQBUF failed");
        return -1;
    }

    bool ok = true;
    const char *error_prefix = NULL;
    int saved_errno = 0;

    if (buf.index >= ctx->buffer_count) {
        ok = false;
        error_prefix = "Invalid V4L2 buffer index";
        saved_errno = EIO;
    } else {
        uint32_t bytes_used = buf.bytesused;
        uint32_t expected = estimate_frame_size(ctx);
        uint32_t mapped_len = (uint32_t) ctx->buffers[buf.index].length;

        // Amlogic vendor driver may report 0/small bytesused for valid raw frames.
        // Fallback to expected frame size to match vendor HAL behavior.
        if (bytes_used == 0 || (expected > 0 && bytes_used < expected / 4)) {
            if (expected > 0) {
                bytes_used = expected;
            } else if (ctx->frame_capacity > 0) {
                bytes_used = ctx->frame_capacity;
            } else {
                bytes_used = mapped_len;
            }
        }

        if (bytes_used > mapped_len) {
            bytes_used = mapped_len;
        }
        if (bytes_used > (uint32_t) dst_capacity) {
            ok = false;
            error_prefix = "Frame buffer too small";
            saved_errno = EMSGSIZE;
        } else {
            const uint8_t *src = (const uint8_t *) ctx->buffers[buf.index].address;
            if (ctx->microdimming_enabled) {
                if (is_yuv420_family(ctx->pixel_format)) {
                    amlogic_apply_microdimming_yuv420(src, dst, ctx->width, ctx->height, ctx->bytes_per_line, bytes_used);
                } else {
                    if (!ctx->microdimming_warned) {
                        LOGW("microdimming requested but unsupported pixel format 0x%x, passthrough frame", ctx->pixel_format);
                        ctx->microdimming_warned = true;
                    }
                    memcpy(dst, src, bytes_used);
                }
            } else {
                memcpy(dst, src, bytes_used);
            }

            int64_t pts_us = (int64_t) buf.timestamp.tv_sec * 1000000LL + (int64_t) buf.timestamp.tv_usec;
            if (pts_us <= 0) {
                struct timespec ts;
                clock_gettime(CLOCK_MONOTONIC, &ts);
                pts_us = (int64_t) ts.tv_sec * 1000000LL + (int64_t) ts.tv_nsec / 1000LL;
            }

            jlong flags = 0;
            if ((buf.flags & V4L2_BUF_FLAG_KEYFRAME) != 0) {
                flags |= FRAME_FLAG_KEYFRAME;
            }

            if (frame_info && env->GetArrayLength(frame_info) >= 3) {
                jlong values[3];
                values[FRAME_INFO_PTS_US] = (jlong) pts_us;
                values[FRAME_INFO_FLAGS] = flags;
                values[FRAME_INFO_INDEX] = buf.index;
                env->SetLongArrayRegion(frame_info, 0, 3, values);
            }

            ctx->refcounts[buf.index] += 1;
            return (jint) bytes_used;
        }
    }

    if (!ok) {
        // Return buffer immediately on copy failure
        if (buf.index < ctx->buffer_count) {
            xioctl(ctx->fd, VIDIOC_QBUF, &buf);
        }
        errno = saved_errno;
        throw_io_exception(env, error_prefix);
        return -1;
    }

    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_genymobile_scrcpy_video_AmlogicV4l2CaptureNative_nativeReleaseFrame(JNIEnv *env, jclass clazz,
                                                                       jlong handle, jint buffer_index) {
    (void) clazz;

    CaptureContext *ctx = (CaptureContext *) (intptr_t) handle;
    if (!ctx || ctx->fd < 0) {
        errno = EINVAL;
        throw_io_exception(env, "Invalid capture handle");
        return;
    }
    if (buffer_index < 0 || (uint32_t) buffer_index >= ctx->buffer_count) {
        errno = EINVAL;
        throw_io_exception(env, "Invalid V4L2 buffer index");
        return;
    }

    uint32_t index = (uint32_t) buffer_index;
    if (ctx->refcounts[index] == 0) {
        return;
    }
    ctx->refcounts[index] -= 1;
    if (ctx->refcounts[index] != 0) {
        return;
    }

    struct v4l2_buffer buf;
    memset(&buf, 0, sizeof(buf));
    buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    buf.memory = V4L2_MEMORY_MMAP;
    buf.index = index;
    if (xioctl(ctx->fd, VIDIOC_QBUF, &buf) < 0) {
        throw_io_exception(env, "VIDIOC_QBUF failed");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_genymobile_scrcpy_video_AmlogicV4l2CaptureNative_nativeClose(JNIEnv *env, jclass clazz,
                                                                jlong handle) {
    (void) env;
    (void) clazz;
    CaptureContext *ctx = (CaptureContext *) (intptr_t) handle;
    close_context(ctx);
}
