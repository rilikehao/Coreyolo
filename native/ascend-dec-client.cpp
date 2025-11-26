extern "C" {
#include "native.h"
}

#include <unistd.h>

#include <QDataStream>
#include <QDir>
#include <QGuiApplication>
#include <QProcess>

#include "io.h"

struct DecoderProcess {
    QProcess data_;
    FILE *stdin_, *stdout_;
};

struct DecoderProcess* StartDecoder(  //
    int device, const char* decodeType, int width, int height) {
    // 1. 创建两个管道
    // pipe_in:  Parent Write -> Child Read
    // pipe_out: Parent Read  <- Child Write
    int pipe_in[2];   // [0]=read, [1]=write
    int pipe_out[2];  // [0]=read, [1]=write

    if (pipe(pipe_in) == -1 || pipe(pipe_out) == -1) {
        throw std::runtime_error("Failed to create pipes");
    }

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
    process->data_.setStandardInputFile(
        QString("/proc/self/fd/%1").arg(pipe_in[0]));
    process->data_.setStandardOutputFile(
        QString("/proc/self/fd/%1").arg(pipe_out[1]));
    process->data_.setProcessChannelMode(
        QProcess::ForwardedErrorChannel);
    process->data_.start(ld, args);
    if (!process->data_.waitForStarted(-1)) {
        throw std::runtime_error("Failed to start decoder process");
    }
    process->stdin_ = fdopen(pipe_in[1], "wb");
    if (!process->stdin_) {
        throw std::runtime_error("fdopen stdin failed");
    }
    process->stdout_ = fdopen(pipe_out[0], "rb");
    if (!process->stdout_) {
        throw std::runtime_error("fdopen stdout failed");
    }
    close(pipe_in[0]);
    close(pipe_out[1]);
    return process;
}

void StopDecoder(struct DecoderProcess* process) {
    process->data_.waitForFinished(-1);
    delete process;
}

void DestroyDecoderIO(struct DecoderIO* io) { delete[] io->data_; }

void DecoderR(struct DecoderProcess* process, struct DecoderIO* io) {
    Read(process->stdout_, &io->timestamp_);
    Read(process->stdout_, &io->size_);
    Read(process->stdout_, io->data_ = new char[io->size_], io->size_);
}

void DecoderW(struct DecoderProcess* process, struct DecoderIO* io) {
    Write(process->stdin_, &io->timestamp_);
    Write(process->stdin_, &io->size_);
    Write(process->stdin_, io->data_, io->size_);
    Flush(process->stdin_);
}
