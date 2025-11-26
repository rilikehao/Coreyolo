#pragma once

#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>
#include <vector>
#include <string>

extern int g_launcherSocket;

void InitProcessLauncher();
void SendCommand(int sock, const std::vector<std::string>& args, int fd_stdin, int fd_stdout);
void RunLauncherLoop(int sock);