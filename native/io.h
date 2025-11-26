#ifndef IO_H
#define IO_H

#include <fcntl.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <stdexcept>

// 读取指定长度的数据
template <typename T>
void Read(int fd, T* data, size_t size = sizeof(T)) {
    char* buffer = reinterpret_cast<char*>(data);
    while (size > 0) {
        ssize_t n = ::read(fd, buffer, size);
        if (n <= 0) {
            if (errno == EINTR) continue;
            std::string message("Read: ");
            message += std::strerror(errno);
            throw std::runtime_error(message);
        }
        buffer += n;
        size -= n;
    }
}

// 写入指定长度的数据
template <typename T>
void Write(int fd, const T* data, size_t size = sizeof(T)) {
    const char* buffer = reinterpret_cast<const char*>(data);
    while (size > 0) {
        ssize_t n = ::write(fd, buffer, size);
        if (n <= 0) {
            if (errno == EINTR) continue;
            std::string message("Write: ");
            message += std::strerror(errno);
            throw std::runtime_error(message);
        }
        buffer += n;
        size -= n;
    }
}

#endif  // IO_H