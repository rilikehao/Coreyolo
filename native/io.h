#ifndef IO_H
#define IO_H

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <stdexcept>

// 从 FILE* 读取指定长度的数据
template <typename T>
void Read(FILE* fp, T* data, size_t size = sizeof(T)) {
    char* buffer = reinterpret_cast<char*>(data);
    while (size > 0) {
        size_t n = std::fread(buffer, 1, size, fp);
        if (n == 0) throw std::runtime_error(strerror(errno));
        buffer += n;
        size -= n;
    }
}

// 向 FILE* 写入指定长度的数据
template <typename T>
bool Write(FILE* fp, const T* data, size_t size = sizeof(T)) {
    const char* buffer = reinterpret_cast<const char*>(data);
    while (size > 0) {
        size_t n = std::fwrite(buffer, 1, size, fp);
        if (n == 0) throw std::runtime_error(strerror(errno));
        buffer += n;
        size -= n;
    }
}

// 专门用于强制刷新缓冲区的辅助函数
void Flush(FILE* fp) {
    if (std::fflush(fp) != 0) throw std::runtime_error(strerror(errno));
}

#endif  // IO_H