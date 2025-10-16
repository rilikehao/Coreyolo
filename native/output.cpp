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
    QLabel *text_, *image_;
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
    QHBoxLayout* info_layout = new QHBoxLayout();
    out->text_ = new QLabel("FPS: 0.0", &out->window_);
    info_layout->addWidget(out->text_);
    info_layout->addStretch();
    main_layout->addLayout(info_layout);
    out->window_.showMaximized();
    return out;
}

void DestroyOutput(Output* out) { delete out; }

void SendToOutput(Output* out, Image* image, const char* text) {
    QMetaObject::invokeMethod(
        QApplication::instance(),
        [out, qImage = image->data_.copy(), s = QString(text)] {
            out->image_->setPixmap(
                QPixmap::fromImage(qImage)
                    .scaled(out->image_->size(), Qt::KeepAspectRatio,
                            Qt::SmoothTransformation));
            out->text_->setText(s);
        },
        Qt::QueuedConnection);
}
