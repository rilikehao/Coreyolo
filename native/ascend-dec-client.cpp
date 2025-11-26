extern "C" {
#include "native.h"
}

#include <QDataStream>
#include <QDir>
#include <QGuiApplication>
#include <QProcess>

#include "io.h"

struct DecoderProcess {
    QProcess data_;
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
    return process;
}

void StopDecoder(struct DecoderProcess* process) {
    process->data_.waitForFinished(-1);
    delete process;
}

void DestroyDecoderIO(struct DecoderIO* io) { delete[] io->data_; }

void DecoderR(struct DecoderProcess* process, struct DecoderIO* io) {
    Read(process->data_, &io->timestamp_);
    Read(process->data_, &io->size_);
    Read(process->data_, io->data_ = new char[io->size_], io->size_);
}

void DecoderW(struct DecoderProcess* process, struct DecoderIO* io) {
    Write(process->data_, &io->timestamp_);
    Write(process->data_, &io->size_);
    Write(process->data_, io->data_, io->size_);
}
