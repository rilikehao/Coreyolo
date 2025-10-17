extern "C" {
#include "native.h"
}

#include <QApplication>
#include <QLabel>
#include <QMainWindow>
#include <QMetaObject>
#include <QString>
#include <QVBoxLayout>

#include "image.h"

struct Output {
    QMainWindow window_;
    QLabel* image_;
};

Output* CreateOutput() {
    auto out = new Output;
    QWidget* central_widget = new QWidget(&out->window_);
    out->window_.setCentralWidget(central_widget);
    auto main_layout = new QVBoxLayout(central_widget);
    out->image_ = new QLabel(&out->window_);
    out->image_->setAlignment(Qt::AlignCenter);
    out->image_->setText("Waiting for video stream...");
    main_layout->addWidget(out->image_, 1);
    out->window_.showMaximized();
    return out;
}

void DestroyOutput(Output* out) { delete out; }

void SendToOutput(Output* out, Image* image) {
    QMetaObject::invokeMethod(
        QApplication::instance(),
        [out, image] {
            out->image_->setPixmap(
                QPixmap::fromImage(image->data_)
                    .scaled(out->image_->size(), Qt::KeepAspectRatio,
                            Qt::SmoothTransformation));
            DestroyImage(image);
        },
        Qt::QueuedConnection);
}
