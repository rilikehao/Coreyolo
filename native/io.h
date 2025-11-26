#ifndef IO_H
#define IO_H

#include <QIODevice>

// 确保读取指定长度的数据，不然就阻塞等待
template <typename T>
bool Read(QIODevice& io, T* data,  //
          qint64 pendingRead = sizeof(*data)) {
    auto buffer = reinterpret_cast<char*>(data);
    while (pendingRead != 0) {
        qint64 read = io.read(buffer, pendingRead);
        if (read < 0 || read == 0 && io.waitForReadyRead(-1)) {
            throw std::runtime_error("Read");
        }
        buffer += read;
        pendingRead -= read;
    }
    return true;
}

// 确保写入指定长度的数据，不然就阻塞等待
template <typename T>
bool Write(QIODevice& io, const T* data,
           qint64 pendingWrite = sizeof(*data)) {
    qDebug() << "write" << pendingWrite;
    auto buffer = reinterpret_cast<const char*>(data);
    while (pendingWrite != 0) {
        qint64 written = io.write(buffer, pendingWrite);
        if (written < 0) {
            throw std::runtime_error("Write");
        }
        while (io.bytesToWrite() != 0) {
            if (!io.waitForBytesWritten(-1)) {
                throw std::runtime_error("Write");
            }
        }
        buffer += written;
        pendingWrite -= written;
    }
    return true;
}

#endif  // IO_H
