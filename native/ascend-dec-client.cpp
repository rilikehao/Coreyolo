extern "C" {
#include "native.h"
}

#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <QDataStream>
#include <QDir>
#include <QGuiApplication>
#include <QProcess>
#include <cstring>
#include <stdexcept>
#include <vector>

#include "io.h"

struct DecoderProcess {
    pid_t pid;      // 子进程 ID
    FILE* stdin_;   // 父进程写（发数据给解码器）
    FILE* stdout_;  // 父进程读（从解码器收数据）
};

struct DecoderProcess* StartDecoder(int device, const char* decodeType,
                                    int width, int height) {
    int p_stdin[2], p_stdout[2];

    // 1. 创建管道
    if (pipe(p_stdin) != 0 || pipe(p_stdout) != 0) {
        throw std::runtime_error("pipe failed");
    }

    // 2. Fork 进程
    pid_t pid = fork();
    if (pid < 0) {
        throw std::runtime_error("fork failed");
    }

    if (pid == 0) {
        // ========== 子进程 context ==========

        // A. 重定向 stdin (将 p_stdin[0] 复制到 STDIN_FILENO)
        if (dup2(p_stdin[0], STDIN_FILENO) == -1) exit(errno);

        // B. 重定向 stdout (将 p_stdout[1] 复制到 STDOUT_FILENO)
        if (dup2(p_stdout[1], STDOUT_FILENO) == -1) exit(errno);

        // C. 关闭所有原始管道 fd（因为已经 dup 到了 0 和
        // 1，原始的不再需要） 同时也必须关闭父进程端（p_stdin[1],
        // p_stdout[0]），否则 pipe 永远不会关闭导致死锁
        close(p_stdin[0]);
        close(p_stdin[1]);
        close(p_stdout[0]);
        close(p_stdout[1]);

        // D. 准备参数 (将 QString 转换为 char*)
        QString appPath = QGuiApplication::applicationDirPath();
        QDir dir(appPath);

        // 构造参数列表
        std::vector<std::string>
            argsStore;  // 保持 string 内存存活直到 execv
        std::vector<char*> argv;

        auto addArg = [&](QString s) {
            argsStore.push_back(s.toStdString());
            argv.push_back(const_cast<char*>(argsStore.back().data()));
        };

        // 你的原始调用逻辑： ld-linux... --library-path ... ./dec ...
        QString exe = dir.absoluteFilePath("ld-linux-aarch64.so.1");
        addArg(exe);  // argv[0]

        addArg("--library-path");
        QStringList libraryPath;
        libraryPath.append(dir.absoluteFilePath("../lib"));
        libraryPath.append(dir.absoluteFilePath("../usr/lib"));
        libraryPath.append(dir.absoluteFilePath("../usr/lib/libproxy"));
        libraryPath.append(dir.absoluteFilePath("../usr/local/lib"));
        addArg(libraryPath.join(":"));

        addArg(dir.absoluteFilePath("../usr/local/ascend/bin/dec"));
        addArg(QString::number(device));
        addArg(decodeType);
        addArg(QString::number(width));
        addArg(QString::number(height));

        argv.push_back(nullptr);  // execv 要求 NULL 结尾

        // E. 执行程序 (如果成功，这里就不会返回)
        execv(exe.toLocal8Bit().constData(), argv.data());

        // F. 如果到了这里，说明 execv 失败了
        perror("execv failed");
        exit(127);
    }

    // ========== 父进程 context ==========

    // 3. 关闭父进程不需要的管道端
    // 父进程只写 stdin（管道写端 p_stdin[1]）
    // 父进程只读 stdout（管道读端 p_stdout[0]）
    close(p_stdin[0]);   // 关闭子进程读的那一端
    close(p_stdout[1]);  // 关闭子进程写的那一端

    auto process = new DecoderProcess;
    process->pid = pid;

    // 4. 将 FD 转换为 FILE*
    process->stdin_ = fdopen(p_stdin[1], "wb");
    if (!process->stdin_) {
        // 清理资源
        close(p_stdin[1]);
        close(p_stdout[0]);
        kill(pid, SIGKILL);
        delete process;
        throw std::runtime_error("fdopen stdin failed");
    }

    process->stdout_ = fdopen(p_stdout[0], "rb");
    if (!process->stdout_) {
        fclose(process->stdin_);  // 这会同时关闭 p_stdin[1]
        close(p_stdout[0]);
        kill(pid, SIGKILL);
        delete process;
        throw std::runtime_error("fdopen stdout failed");
    }

    return process;
}

void StopDecoder(struct DecoderProcess* process) {
    if (!process) return;

    // 1. 关闭管道，向子进程发送 EOF
    if (process->stdin_) fclose(process->stdin_);
    if (process->stdout_) fclose(process->stdout_);

    // 2. 等待子进程退出
    int status;
    waitpid(process->pid, &status, 0);

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
