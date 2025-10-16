extern "C" {
#include "native.h"
}

#include <QNetworkAccessManager>
#include <QPainter>

#include "image.h"

void DrawRect(Image* image, Rect* rect, int r, int g, int b, const char* s) {
    QPainter painter(&image->data_);
    painter.setPen(QPen(QColor(r, g, b), 3));
    painter.drawRect(rect->x0_, rect->y0_,  //
                     rect->x1_ - rect->x0_, rect->y1_ - rect->y0_);
    if (*s) {
        painter.setPen(QPen(QColor(r, g, b), 1));
        QFont font = painter.font();
        font.setPointSize(40);  // Set font size to 40 points
        painter.setFont(font);
        painter.drawText(rect->x0_ + 5, rect->y0_ + 45, QString(s));
    }
    painter.end();
}

void HttpGet(const char* url) {
    QNetworkAccessManager manager;
    QNetworkRequest request(QUrl(QString::fromUtf8(url)));
    manager.get(request);
}
