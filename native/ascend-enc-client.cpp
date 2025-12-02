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

struct EncoderProcess {
    pid_t pid_;
    int to_encoder_, from_encoder_;
};

extern "C" {

struct EncoderProcess* StartEncoder(  //
    int device, int id, const char* encodeType, int width, int height) {
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
    auto enc = "../usr/local/ascend" SUFFIX "/bin/enc";
    addArg(dir.absoluteFilePath(enc));
    addArg(QString::number(device));
    addArg(QString::number(id));
    addArg(QString::fromUtf8(encodeType));
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

    EncoderProcess* process = new EncoderProcess;
    process->pid_ = pid;

    int flags0 = fcntl(p_stdout[0], F_GETFL);
    flags0 &= ~O_NONBLOCK;
    fcntl(p_stdout[0], F_SETFL, flags0);
    process->from_encoder_ = p_stdout[0];

    int flags1 = fcntl(p_stdin[1], F_GETFL);
    flags1 &= ~O_NONBLOCK;
    fcntl(p_stdin[1], F_SETFL, flags1);
    process->to_encoder_ = p_stdin[1];

    return process;
}

void StopEncoder(struct EncoderProcess* process) {
    ::close(process->to_encoder_);
    ::close(process->from_encoder_);

    if (process->pid_ > 0) {
        ::kill(process->pid_, SIGKILL);
        ::waitpid(process->pid_, nullptr, 0);
    }

    delete process;
}

void EncoderR0(struct EncoderProcess* process, struct EncoderIO* io) {
    Read(process->from_encoder_, &io->timestamp_);
    Read(process->from_encoder_, &io->is_key_frame_);
    Read(process->from_encoder_, &io->size_);
}

void EncoderR1(struct EncoderProcess* process, struct EncoderIO* io) {
    Read(process->from_encoder_, io->data_, io->size_);
}

bool EncoderW(struct EncoderProcess* process, struct EncoderIO* io) {
    try {
        Write(process->to_encoder_, &io->timestamp_);
        Write(process->to_encoder_, &io->is_key_frame_);
        Write(process->to_encoder_, &io->size_);
        Write(process->to_encoder_, io->data_, io->size_);
    } catch (const std::runtime_error& e) {
        return false;
    }
    return true;
}

}  // extern