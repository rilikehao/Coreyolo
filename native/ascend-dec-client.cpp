extern "C" {
#include "native.h"
}

#include <QDataStream>
#include <QDir>
#include <QGuiApplication>
#include <QProcess>

struct DecoderProcess {
    QProcess data_;
    std::unique_ptr<QDataStream> stream_;
};

struct DecoderProcess* StartDecoder(  //
    int device, const char* decodeType, int width, int height) {
    QString appPath = QGuiApplication::applicationDirPath();
    QDir dir(appPath);
    QString ld = dir.absoluteFilePath("ld-linux-aarch64.so.1");
    QStringList args;
    args.append("--library-path");
    QStringList libraryPath;
    libraryPath.append(dir.absoluteFilePath("../lib"));
    libraryPath.append(dir.absoluteFilePath("../usr/lib"));
    libraryPath.append(dir.absoluteFilePath("../usr/lib/libproxy"));
    libraryPath.append(dir.absoluteFilePath("../usr/local/lib"));
    args.append(libraryPath.join(":"));
    args.append(dir.absoluteFilePath("../usr/local/ascend/bin/dec"));
    args.append(QString::number(device));
    args.append(decodeType);
    args.append(QString::number(width));
    args.append(QString::number(height));
    auto process = new DecoderProcess;
    process->data_.setProcessChannelMode(
        QProcess::ForwardedErrorChannel);
    process->data_.start(ld, args);
    process->data_.waitForStarted(-1);
    process->stream_ = std::make_unique<QDataStream>(&process->data_);
    return process;
}

void StopDecoder(struct DecoderProcess* process) {
    process->data_.waitForFinished(-1);
    delete process;
}

void DestroyDecoderIO(struct DecoderIO* io) { delete[] io->data_; }

bool DecoderR(struct DecoderProcess* process, struct DecoderIO* io) {
    auto timestamp = reinterpret_cast<qint64*>(&io->timestamp_);
    auto size = reinterpret_cast<qint64*>(&io->size_);
    process->stream_->startTransaction();
    (*process->stream_) >> (*timestamp);
    process->stream_->readBytes(io->data_, *size);
    qDebug() << "read" << (*timestamp);
    return process->stream_->commitTransaction();
}

void DecoderW(struct DecoderProcess* process, struct DecoderIO* io) {
    (*process->stream_) << qint64(io->timestamp_);
    process->stream_->writeBytes(io->data_, io->size_);
    process->data_.waitForBytesWritten(-1);
    qDebug() << "write" << io->timestamp_;
}
