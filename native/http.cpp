extern "C" {
#include "native.h"
}

#include <QBuffer>
#include <QEventLoop>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QTcpServer>
#include <QThread>
#include <QTimeZone>
#include <QUrlQuery>

#include "image.h"

struct TcpSocket {
    QTcpSocket* data_;
};

struct HttpServerThread {
    QThread data_;
};

namespace {

#include <QDateTime>
#include <QTimeZone>
#include <climits>

int64_t EpochMs(const QString& s) {
    auto t = QDateTime::fromString(s, Qt::ISODateWithMs);
    if (!t.isValid()) return LONG_LONG_MAX;
    return t.toMSecsSinceEpoch();
}

void AcceptConnection(QTcpServer* tcpServer, Subscribe sub) {
    while (QTcpSocket* socket = tcpServer->nextPendingConnection()) {
        socket->setSocketOption(QAbstractSocket::LowDelayOption, 1);
        QObject::connect(socket, &QTcpSocket::readyRead, socket, [=] {
            auto req = socket->readAll();
            auto split = req.split(' ');
            auto query = QUrlQuery(QUrl::fromEncoded(split[1]));
            auto stream = query.queryItemValue("stream").toUtf8();
            auto begin = EpochMs(query.queryItemValue("begin"));
            auto end = EpochMs(query.queryItemValue("end"));
            auto fast = query.queryItemValue("fast") == "1";
            QByteArray headers =
                "HTTP/1.1 200 OK\r\n"
                "Content-Type: video/webm\r\n"
                "Connection: keep-alive\r\n"
                "Cache-Control: no-cache\r\n"
                "Access-Control-Allow-Origin: *\r\n"
                "Transfer-Encoding: chunked\r\n\r\n";
            socket->write(headers);
            socket->flush();
            auto tcpSocket = new TcpSocket{socket};
            sub.func_(stream.constData(), tcpSocket, sub.opaque_,  //
                      begin, end, fast);
        });
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

bool SendData(TcpSocket* socket, const char* data, int size) {
    QMetaObject::invokeMethod(
        socket->data_,
        [&] {
            if (data &&  //
                socket->data_->state() ==
                    QAbstractSocket::ConnectedState) {
                QByteArray packet;
                packet.reserve(size + 32);
                packet.append(QString::number(size, 16).toLatin1());
                packet.append("\r\n");
                packet.append(data, size);
                packet.append("\r\n");
                socket->data_->write(packet);
                socket->data_->flush();
            } else {
                socket->data_->write("0\r\n\r\n");
                socket->data_->flush();
                socket->data_->close();
                socket->data_->deleteLater();
                delete socket;
                socket = nullptr;
            }
        },
        Qt::BlockingQueuedConnection);
    return socket;
}

HttpServerThread* HttpServer(int port, Subscribe sub) {
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

struct TimeString EpochMsToTimeString(int64_t input) {
    auto t = QDateTime::fromMSecsSinceEpoch(input);
    TimeString output;
    auto data = t.toString(Qt::ISODateWithMs).toUtf8().constData();
    strncpy(output.data_, data, sizeof(output));
    return output;
}

int64_t EpochMsFromTimeString(const char* input) {
    return EpochMs(input);
}

}  // extern
