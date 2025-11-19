extern "C" {
#include "native.h"
}

#include <QBuffer>
#include <QEventLoop>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QTcpServer>
#include <QThread>
#include <QUrlQuery>

#include "image.h"

struct TcpSocket {
    QTcpSocket* data_;
};

struct HttpServerThread {
    QThread data_;
};

namespace {

double ParseTime(const QString& s) {
    if (s.isEmpty()) return INFINITY;
    auto t = QDateTime::fromString(s, Qt::ISODateWithMs);
    return t.toMSecsSinceEpoch() / 1000.0;
}

void AcceptConnection(QTcpServer* tcpServer, Subscribe sub) {
    while (QTcpSocket* socket = tcpServer->nextPendingConnection()) {
        QObject::connect(socket, &QTcpSocket::readyRead, socket, [=] {
            auto req = QUrlQuery(QUrl::fromEncoded(socket->readAll()));
            auto begin = ParseTime(req.queryItemValue("begin"));
            auto end = ParseTime(req.queryItemValue("end"));
            QByteArray headers =
                "HTTP/1.1 200 OK\r\n"
                "Content-Type: video/webm\r\n"
                "Connection: keep-alive\r\n"
                "Cache-Control: no-cache\r\n"
                "Access-Control-Allow-Origin: *\r\n"
                "Transfer-Encoding: chunked\r\n\r\n";
            socket->write(headers);
            sub.func_(new TcpSocket{socket}, sub.opaque_);
        });
        QObject::connect(socket, &QTcpSocket::disconnected,  //
                         socket, &QObject::deleteLater);
    }
}

}  // namespace

extern "C" {

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

void HttpPost(const char* url, struct Image* image) {
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

void SendData(TcpSocket* socket, const char* data, int size) {
    if (data) {
        auto number = QString::number(size, 16).toLatin1();
        socket->data_->write(number);
        socket->data_->write("\r\n");
        socket->data_->write(data, size);
        socket->data_->write("\r\n");
    } else {
        socket->data_->close();
        delete socket;
    }
}

HttpServerThread* StartHttpServer(int port, Subscribe sub) {
    auto thread = new HttpServerThread;
    auto worker = new QObject;
    worker->moveToThread(&thread->data_);
    QObject::connect(&thread->data_, &QThread::started, worker, [=] {
        auto tcpServer = new QTcpServer(worker);
        if (!tcpServer->listen(QHostAddress::Any, port)) {
            throw std::runtime_error("启动 Http Server 失败");
        }
        qDebug("Server listening on port %d", port);
        QObject::connect(                           //
            tcpServer, &QTcpServer::newConnection,  //
            worker, [=] { AcceptConnection(tcpServer, sub); });
        QObject::connect(&thread->data_, &QThread::finished, worker,
                         &QObject::deleteLater);
    });
    thread->data_.start();
    return thread;
}

void StopHttpServer(HttpServerThread* thread) {
    thread->data_.quit();
    thread->data_.wait();
    delete thread;
}

}  // extern
