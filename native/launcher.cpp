#include "launcher.h"
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>
#include <string>
#include <cstring>
#include <iostream>
#include <signal.h>

int g_launcherSocket = -1;

void SendCommand(int sock, const std::vector<std::string>& args, int fd_stdin, int fd_stdout) {
    struct msghdr msg = {0};
    struct iovec iov[1];

    std::vector<char> dataBuffer;
    int argc = args.size();
    dataBuffer.insert(dataBuffer.end(), (char*)&argc, (char*)&argc + sizeof(int));

    for (const auto& arg : args) {
        dataBuffer.insert(dataBuffer.end(), arg.begin(), arg.end());
        dataBuffer.push_back('\0');
    }

    iov[0].iov_base = dataBuffer.data();
    iov[0].iov_len = dataBuffer.size();
    msg.msg_iov = iov;
    msg.msg_iovlen = 1;

    union {
        char buf[CMSG_SPACE(2 * sizeof(int))];
        struct cmsghdr align;
    } u;

    msg.msg_control = u.buf;
    msg.msg_controllen = sizeof(u.buf);

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(2 * sizeof(int));

    int *fds = (int *)CMSG_DATA(cmsg);
    fds[0] = fd_stdin;
    fds[1] = fd_stdout;

    if (sendmsg(sock, &msg, 0) < 0) {
        perror("Send launcher command failed");
    }
}

void RunLauncherLoop(int sock) {
    signal(SIGCHLD, SIG_IGN);

    while (true) {
        struct msghdr msg = {0};
        char buffer[4096];
        struct iovec iov[1];
        iov[0].iov_base = buffer;
        iov[0].iov_len = sizeof(buffer);
        msg.msg_iov = iov;
        msg.msg_iovlen = 1;

        union {
            char buf[CMSG_SPACE(2 * sizeof(int))];
            struct cmsghdr align;
        } u;
        msg.msg_control = u.buf;
        msg.msg_controllen = sizeof(u.buf);

        ssize_t n = recvmsg(sock, &msg, 0);
        if (n <= 0) break;

        struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
        if (!cmsg || cmsg->cmsg_len != CMSG_LEN(2 * sizeof(int))) continue;
        int *fds = (int *)CMSG_DATA(cmsg);
        int child_stdin = fds[0];
        int child_stdout = fds[1];

        char* p = buffer;
        int argc = *(int*)p;
        p += sizeof(int);

        std::vector<char*> argv;
        for (int i = 0; i < argc; ++i) {
            argv.push_back(p);
            p += strlen(p) + 1;
        }
        argv.push_back(nullptr);

        pid_t pid = fork();
        if (pid == 0) {
            if (dup2(child_stdin, STDIN_FILENO) == -1) _exit(1);
            if (dup2(child_stdout, STDOUT_FILENO) == -1) _exit(1);

            close(child_stdin);
            close(child_stdout);
            close(sock);

            execv(argv[0], argv.data());
            perror("Launcher exec failed");
            _exit(127);
        }

        close(child_stdin);
        close(child_stdout);

        int pid_reply = pid;
        write(sock, &pid_reply, sizeof(int));
    }
    close(sock);
}

void InitProcessLauncher() {
    int sv[2];
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, sv) < 0) {
        perror("socketpair");
        exit(1);
    }

    pid_t pid = fork();
    if (pid < 0) {
        perror("fork");
        exit(1);
    }

    if (pid == 0) {
        close(sv[0]);
        RunLauncherLoop(sv[1]);
        _exit(0);
    } else {
        close(sv[1]);
        g_launcherSocket = sv[0];
    }
}