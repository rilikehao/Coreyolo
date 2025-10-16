extern "C" {
#include "native.h"
}

#include <linux/videodev2.h>

#include "image.h"

extern "C" {

bool SupportFormat(uint32_t v4l2_format) {
    switch (v4l2_format) {
        case V4L2_PIX_FMT_BGR24:
        case V4L2_PIX_FMT_NV12:
        case V4L2_PIX_FMT_NV16:
        case V4L2_PIX_FMT_YUYV:
        case V4L2_PIX_FMT_MJPEG:  // Motion JPEG format support
        case V4L2_PIX_FMT_JPEG:   // JPEG format support
            return true;
        default:
            return false;
    }
}

void DestroyImage(Image* image) { delete image; }

struct Image* CreateImagePath(const char* path) {
    auto image = new Image;
    image->data_ = QImage(QString::fromUtf8(path));
    return image;
}

struct Image* CreateImageRGB24(int w, int h) {
    auto image = new Image;
    image->data_ = QImage(w, h, QImage::Format_RGB888);
    return image;
}

int BytesPerLine(struct Image* image) {
    return image->data_.bytesPerLine();
}

uint8_t* Bits(struct Image* image) { return image->data_.bits(); }

}  // extern

QImage DecodeMotionJPEG(void* data, int width, int height) {
    size_t max_size = width * height * 3;
    size_t actual_size = 0;
    for (size_t i = 1; i < max_size - 1; i++) {
        if (((unsigned char*)data)[i] == 0xFF &&
            ((unsigned char*)data)[i + 1] == 0xD9) {
            actual_size = i + 2;
            break;
        }
    }
    QImage jpeg_image;
    jpeg_image.loadFromData(
        static_cast<uchar*>(data), actual_size, "JPEG");
    return jpeg_image;
}
