extern "C" {
#include "native.h"
}

#include <linux/videodev2.h>

#include "image.h"

extern "C" {

struct Image* CreateImageRGB24(int w, int h) {
    auto image = new Image;
    image->data_ = QImage(w, h, QImage::Format_RGB888);
    return image;
}

struct Image* CreateImageJPEG(void* data, int max_size) {
    size_t actual_size = 0;
    auto data8 = static_cast<uint8_t*>(data);
    for (size_t i = 1; i < max_size - 1; i++) {
        if (reinterpret_cast<uint16_t*>(data8 + i)[0] == 0xD9FF) {
            actual_size = i + 2;
            break;
        }
    }
    auto result = new Image;
    result->data_.loadFromData(data8, actual_size, "JPEG");
    result->data_ =
        result->data_.convertToFormat(QImage::Format_RGB888);
    return result;
}

struct Image* CreateImagePath(const char* path) {
    auto image = new Image;
    image->data_ = QImage(QString::fromUtf8(path));
    return image;
}

struct Image* CreateImageCopy(struct Image* origin) {
    auto image = new Image;
    image->data_ = origin->data_.copy();
    return image;
}

void DestroyImage(Image* image) { delete image; }

int BytesPerLine(struct Image* image) {
    return image->data_.bytesPerLine();
}

uint8_t* Bits(struct Image* image) { return image->data_.bits(); }

int GetWidth(struct Image* image) { return image->data_.width(); }
int GetHeight(struct Image* image) { return image->data_.height(); }

}  // extern
