extern "C" {
#include "native.h"
}

#include <fcntl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <QDir>
#include <QGuiApplication>
#include <stdexcept>
#include <vector>

#include "io.h"
#include "launcher.h"

struct DecoderProcess {
    pid_t pid_;
    int to_decoder_, from_decoder_;
};

extern "C" {

struct DecoderProcess* StartDecoder(  //
    int device, int id, const char* decodeType, int width, int height) {
    ::signal(SIGPIPE, SIG_IGN);
    QString appPath = QGuiApplication::applicationDirPath();
    QDir dir(appPath);
    std::vector<std::string> args;

    auto addArg = [&](const QString& s) {
        args.emplace_back(s.toStdString());
    };

    auto exe = dir.absoluteFilePath("ld-linux-aarch64.so.1");
    addArg(exe);
    addArg("--library-path");
    QStringList libraryPath;
    libraryPath.append(dir.absoluteFilePath("../lib"));
    libraryPath.append(dir.absoluteFilePath("../usr/lib"));
    libraryPath.append(dir.absoluteFilePath("../usr/lib/libproxy"));
    libraryPath.append(dir.absoluteFilePath("../usr/local/lib"));
    auto ldPath = qgetenv("LD_LIBRARY_PATH");
    if (!ldPath.isEmpty()) {
        libraryPath.append(QString::fromLocal8Bit(ldPath));
    }
    addArg(libraryPath.join(":"));
    auto dec = "../usr/local/ascend" SUFFIX "/bin/dec";
    addArg(dir.absoluteFilePath(dec));
    addArg(QString::number(device));
    addArg(QString::number(id));
    addArg(QString::fromUtf8(decodeType));
    addArg(QString::number(width));
    addArg(QString::number(height));

    int p_stdin[2];
    int p_stdout[2];
    ::pipe2(p_stdin, O_CLOEXEC);
    ::pipe2(p_stdout, O_CLOEXEC);

    SendCommand(g_launcherSocket, args, p_stdin[0], p_stdout[1]);

    pid_t pid = -1;
    ::read(g_launcherSocket, &pid, sizeof(pid));

    ::close(p_stdin[0]);
    ::close(p_stdout[1]);

    DecoderProcess* process = new DecoderProcess;
    process->pid_ = pid;

    int flags0 = fcntl(p_stdout[0], F_GETFL);
    flags0 &= ~O_NONBLOCK;
    fcntl(p_stdout[0], F_SETFL, flags0);
    process->from_decoder_ = p_stdout[0];

    int flags1 = fcntl(p_stdin[1], F_GETFL);
    flags1 &= ~O_NONBLOCK;
    fcntl(p_stdin[1], F_SETFL, flags1);
    process->to_decoder_ = p_stdin[1];

    return process;
}

void StopDecoder(struct DecoderProcess* process) {
    ::close(process->to_decoder_);
    ::close(process->from_decoder_);

    qDebug() << "StopDecoder" << process->pid_;

    if (process->pid_ > 0) {
        ::kill(process->pid_, SIGKILL);
        ::waitpid(process->pid_, nullptr, 0);
    }

    delete process;
}

void DestroyDecoderIO(struct DecoderIO* io) {
    if (!io) return;
    delete[] io->data_;
    io->data_ = nullptr;
}

void DecoderR(struct DecoderProcess* process, struct DecoderIO* io) {
    Read(process->from_decoder_, &io->timestamp_);
    Read(process->from_decoder_, &io->size_);
    io->data_ = new char[io->size_];
    Read(process->from_decoder_, io->data_, io->size_);
}

void DecoderW(struct DecoderProcess* process, struct DecoderIO* io) {
    Write(process->to_decoder_, &io->timestamp_);
    Write(process->to_decoder_, &io->size_);
    Write(process->to_decoder_, io->data_, io->size_);
}

}  // extern
