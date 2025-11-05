extern "C" {
#include "native.h"
}

#include <QBuffer>
#include <QEventLoop>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QPainter>
#include <QThread>

#include "image.h"

extern "C" {

void DrawRect(Image* image, Rect* rect, int r, int g, int b,
              const char* s) {
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
    QNetworkRequest request(QUrl(QString::fromUtf8(url)));
    auto thread = new QThread;
    auto worker = new QObject;
    worker->moveToThread(thread);
    QObject::connect(thread, &QThread::started, worker, [=] {
        auto manager = new QNetworkAccessManager;
        auto reply = manager->get(request);
        QObject::connect(reply, &QNetworkReply::finished, worker, [=] {
            if (reply->error() != QNetworkReply::NoError) {
                qWarning() << "Error:" << reply->errorString();
            } else {
                auto attr = QNetworkRequest::HttpStatusCodeAttribute;
                int status = reply->attribute(attr).toInt();
                qDebug() << "HTTP Status:" << status;
            }
            reply->deleteLater();
            manager->deleteLater();
            worker->deleteLater();
            thread->quit();
        });
        QObject::connect(
            thread, &QThread::finished, thread, &QObject::deleteLater);
    });
    thread->start();
}

int HttpGetWaitStatus(const char* url) {
    int status;
    QNetworkRequest request(QUrl(QString::fromUtf8(url)));
    auto thread = new QThread;
    auto worker = new QObject;
    worker->moveToThread(thread);
    QObject::connect(thread, &QThread::started, worker, [=, &status] {
        auto manager = new QNetworkAccessManager;
        auto reply = manager->get(request);
        QObject::connect(
            reply, &QNetworkReply::finished, worker, [=, &status] {
                QString result;
                if (reply->error() != QNetworkReply::NoError) {
                    qWarning() << "Error:" << reply->errorString();
                } else {
                    auto attr =
                        QNetworkRequest::HttpStatusCodeAttribute;
                    status = reply->attribute(attr).toInt();
                    qDebug() << "HTTP Status:" << status;
                }
                reply->deleteLater();
                manager->deleteLater();
                worker->deleteLater();
                thread->quit();
            });
        QObject::connect(
            thread, &QThread::finished, thread, &QObject::deleteLater);
    });
    thread->start();
    thread->wait();
    return status;
}

void HttpPost(const char* url, Image* image) {
    QNetworkRequest request(QUrl(QString::fromUtf8(url)));
    request.setHeader(QNetworkRequest::ContentTypeHeader, "image/jpeg");
    QByteArray payload;
    QBuffer buffer(&payload);
    buffer.open(QIODevice::WriteOnly);
    image->data_.save(&buffer, "JPEG");
    buffer.close();

    auto thread = new QThread;
    auto worker = new QObject;
    worker->moveToThread(thread);
    QObject::connect(thread, &QThread::started, worker, [=] {
        auto manager = new QNetworkAccessManager;
        auto reply = manager->post(request, payload);
        QObject::connect(reply, &QNetworkReply::finished, worker, [=] {
            if (reply->error() != QNetworkReply::NoError) {
                qWarning() << "Error:" << reply->errorString();
            } else {
                auto attr = QNetworkRequest::HttpStatusCodeAttribute;
                int status = reply->attribute(attr).toInt();
                qDebug() << "HTTP Status:" << status;
            }
            reply->deleteLater();
            manager->deleteLater();
            worker->deleteLater();
            thread->quit();
        });
        QObject::connect(
            thread, &QThread::finished, thread, &QObject::deleteLater);
    });
    thread->start();
}

}  // extern
