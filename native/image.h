#ifndef IMAGE_H
#define IMAGE_H

#include <QImage>

struct Image {
    QImage data_;
};

QImage DecodeMotionJPEG(void* data, int width, int height);

#endif  // IMAGE_H
