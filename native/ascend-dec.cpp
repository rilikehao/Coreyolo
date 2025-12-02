#include <acl/acl.h>
#include <acl/ops/acl_dvpp.h>

#include <QCoreApplication>
#include <QDebug>
#include <QFile>
#include <QThread>
#include <stdexcept>

#include "io.h"

aclrtMemcpyKind upload, download;
int width, height, wstride, hstride;
aclvdecChannelDesc* channel;

QObject aclWorker, callbackWorker;

void Process() {
    aclrtProcessReport(1000);
    QMetaObject::invokeMethod(
        &callbackWorker, [] { Process(); }, Qt::QueuedConnection);
}

void Callback(acldvppStreamDesc* input, acldvppPicDesc* output,
              void* data) {
    QByteArray pic;
    QMetaObject::invokeMethod(
        &aclWorker,
        [&] {
            auto streamDev = acldvppGetStreamDescData(input);
            if (ACL_SUCCESS != acldvppFree(streamDev)) {
                throw std::runtime_error("acldvppFree");
            }
            if (ACL_SUCCESS != acldvppDestroyStreamDesc(input)) {
                throw std::runtime_error("acldvppFree");
            }
            auto picDev = acldvppGetPicDescData(output);
            auto picSize = acldvppGetPicDescSize(output);
            pic.resizeForOverwrite(picSize);
            if (ACL_SUCCESS !=  //
                aclrtMemcpy(
                    pic.data(), picSize, picDev, picSize, download)) {
                throw std::runtime_error("aclrtMemcpy pic");
            }
            if (ACL_SUCCESS != acldvppFree(picDev)) {
                throw std::runtime_error("acldvppFree");
            }
            if (ACL_SUCCESS != acldvppDestroyPicDesc(output)) {
                throw std::runtime_error("acldvppFree");
            }
        },
        Qt::BlockingQueuedConnection);
    auto timestamp = static_cast<qint64*>(data);
    int64_t size = pic.size();
    Write(STDOUT_FILENO, timestamp);
    Write(STDOUT_FILENO, &size);
    Write(STDOUT_FILENO, pic.data(), size);
    delete timestamp;
}

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    int device = strtol(argv[1], nullptr, 10);
    int id = strtol(argv[2], nullptr, 10);
    acldvppStreamFormat decodeType;
    if (strcmp(argv[3], "h264") == 0) decodeType = H264_HIGH_LEVEL;
    if (strcmp(argv[3], "hevc") == 0) decodeType = H265_MAIN_LEVEL;
    width = strtol(argv[4], nullptr, 10);
    height = strtol(argv[5], nullptr, 10);
    wstride = (width + 15) / 16 * 16;
    hstride = (height + 1) / 2 * 2;
    if (ACL_SUCCESS != aclInit(nullptr)) {
        throw std::runtime_error("aclInit");
    }
    aclrtContext context;
    if (ACL_SUCCESS != aclrtCreateContext(&context, device)) {
        throw std::runtime_error("aclrtCreateContext");
    }
    if (ACL_SUCCESS != aclrtSetDevice(device)) {
        throw std::runtime_error("aclrtSetDevice");
    }
    if (ACL_SUCCESS != aclrtSetCurrentContext(context)) {
        throw std::runtime_error("aclrtSetCurrentContext");
    }
    aclrtRunMode runMode;
    if (ACL_SUCCESS != aclrtGetRunMode(&runMode)) {
        throw std::runtime_error("aclrtGetRunMode");
    }
    if (runMode == ACL_HOST) {
        upload = ACL_MEMCPY_HOST_TO_DEVICE;
        download = ACL_MEMCPY_DEVICE_TO_HOST;
    } else {
        upload = ACL_MEMCPY_DEVICE_TO_DEVICE;
        download = ACL_MEMCPY_DEVICE_TO_DEVICE;
    }
    QThread aclThread;
    aclWorker.moveToThread(&aclThread);
    aclThread.start();
    QMetaObject::invokeMethod(
        &aclWorker,
        [&] {
            if (ACL_SUCCESS != aclrtSetDevice(device)) {
                throw std::runtime_error("aclrtSetDevice");
            }
            if (ACL_SUCCESS != aclrtSetCurrentContext(context)) {
                throw std::runtime_error("aclrtSetCurrentContext");
            }
        },
        Qt::BlockingQueuedConnection);
    QThread callbackThread;
    callbackWorker.moveToThread(&callbackThread);
    callbackThread.start();
    pthread_t callbackPid;
    QMetaObject::invokeMethod(
        &callbackWorker,
        [&] {
            callbackPid = pthread_self();
            if (ACL_SUCCESS != aclrtSetDevice(device)) {
                throw std::runtime_error("aclrtSetDevice");
            }
            if (ACL_SUCCESS != aclrtSetCurrentContext(context)) {
                throw std::runtime_error("aclrtSetCurrentContext");
            }
        },
        Qt::BlockingQueuedConnection);
    channel = aclvdecCreateChannelDesc();
    if (!channel) throw std::runtime_error("aclvdecCreateChannelDesc");
    if (ACL_SUCCESS != aclvdecSetChannelDescChannelId(channel, id)) {
        throw std::runtime_error("aclvdecSetChannelDescChannelId");
    }
    if (ACL_SUCCESS !=
        aclvdecSetChannelDescThreadId(channel, callbackPid)) {
        throw std::runtime_error("aclvdecSetChannelDescThreadId");
    }
    if (ACL_SUCCESS !=
        aclvdecSetChannelDescCallback(channel, Callback)) {
        throw std::runtime_error("aclvdecSetChannelDescCallback");
    }
    if (ACL_SUCCESS !=
        aclvdecSetChannelDescEnType(channel, decodeType)) {
        throw std::runtime_error("aclvdecSetChannelDescEnType");
    }
    if (ACL_SUCCESS !=  //
        aclvdecSetChannelDescOutPicFormat(
            channel, PIXEL_FORMAT_YUV_SEMIPLANAR_420)) {
        throw std::runtime_error("aclvdecSetChannelDescOutPicFormat");
    }
    if (ACL_SUCCESS != aclvdecCreateChannel(channel)) {
        throw std::runtime_error("aclvdecCreateChannel");
    }
    QMetaObject::invokeMethod(
        &callbackWorker, [&] { Process(); }, Qt::QueuedConnection);
    while (true) {
        auto timestamp = new qint64;
        QByteArray stream;
        int64_t size;
        Read(STDIN_FILENO, timestamp);
        Read(STDIN_FILENO, &size);
        stream.resizeForOverwrite(size);
        Read(STDIN_FILENO, stream.data(), size);
        if (*timestamp == 0) break;
        acldvppStreamDesc* streamDesc;
        acldvppPicDesc* picDesc;
        QMetaObject::invokeMethod(
            &aclWorker,
            [&] {
                void *streamDev, *picDev;
                if (ACL_SUCCESS !=
                    acldvppMalloc(&streamDev, stream.size())) {
                    throw std::runtime_error("acldvppMalloc");
                }
                if (ACL_SUCCESS !=                         //
                    aclrtMemcpy(streamDev, stream.size(),  //
                                stream.data(), stream.size(), upload)) {
                    throw std::runtime_error("aclrtMemcpy stream");
                }
                streamDesc = acldvppCreateStreamDesc();
                if (!streamDesc) {
                    throw std::runtime_error("acldvppCreateStreamDesc");
                }
                if (ACL_SUCCESS !=
                    acldvppSetStreamDescData(streamDesc, streamDev)) {
                    throw std::runtime_error(
                        "acldvppSetStreamDescData");
                }
                if (ACL_SUCCESS !=  //
                    acldvppSetStreamDescSize(
                        streamDesc, stream.size())) {
                    throw std::runtime_error(
                        "acldvppSetStreamDescSize");
                }
                int picSize = wstride * hstride / 2 * 3;  // NV12
                if (ACL_SUCCESS != acldvppMalloc(&picDev, picSize)) {
                    throw std::runtime_error("acldvppMalloc");
                }
                picDesc = acldvppCreatePicDesc();
                if (!picDesc) {
                    throw std::runtime_error("acldvppCreatePicDesc");
                }
                if (ACL_SUCCESS !=
                    acldvppSetPicDescData(picDesc, picDev)) {
                    throw std::runtime_error("acldvppSetPicDescData");
                }
                if (ACL_SUCCESS !=
                    acldvppSetPicDescSize(picDesc, picSize)) {
                    throw std::runtime_error("acldvppSetPicDescSize");
                }
                if (ACL_SUCCESS !=
                    acldvppSetPicDescFormat(
                        picDesc, PIXEL_FORMAT_YUV_SEMIPLANAR_420)) {
                    throw std::runtime_error("acldvppSetPicDescFormat");
                }
                if (ACL_SUCCESS !=
                    acldvppSetPicDescWidth(picDesc, width)) {
                    throw std::runtime_error("acldvppSetPicDescWidth");
                }
                if (ACL_SUCCESS !=
                    acldvppSetPicDescHeight(picDesc, height)) {
                    throw std::runtime_error("acldvppSetPicDescHeight");
                }
                if (ACL_SUCCESS !=
                    acldvppSetPicDescWidthStride(picDesc, wstride)) {
                    throw std::runtime_error(
                        "acldvppSetPicDescWidthStride");
                }
                if (ACL_SUCCESS !=
                    acldvppSetPicDescHeightStride(picDesc, hstride)) {
                    throw std::runtime_error(
                        "acldvppSetPicDescHeightStride");
                }
            },
            Qt::BlockingQueuedConnection);
        if (ACL_SUCCESS !=  //
            aclvdecSendFrame(
                channel, streamDesc, picDesc, nullptr, timestamp)) {
            qDebug() << "aclvdecSendFrame";
        }
    }
    QMetaObject::invokeMethod(
        &aclWorker,
        [&] {
            auto streamDesc = acldvppCreateStreamDesc();
            if (!streamDesc) {
                throw std::runtime_error("acldvppCreateStreamDesc");
            }
            if (ACL_SUCCESS != acldvppSetStreamDescEos(streamDesc, 1)) {
                throw std::runtime_error("acldvppSetStreamDescEos");
            }
            if (ACL_SUCCESS !=  //
                aclvdecSendFrame(
                    channel, streamDesc, nullptr, nullptr, nullptr)) {
                qDebug() << "aclvdecSendFrame";
            }
            QCoreApplication::instance()->quit();
        },
        Qt::BlockingQueuedConnection);
    aclThread.quit();
    aclThread.wait();
    callbackThread.quit();
    callbackThread.wait();
    if (ACL_SUCCESS != aclvdecDestroyChannel(channel)) {
        throw std::runtime_error("aclvdecDestroyChannel");
    }
    if (ACL_SUCCESS != aclvdecDestroyChannelDesc(channel)) {
        throw std::runtime_error("aclvdecDestroyChannelDesc");
    }
    if (ACL_SUCCESS != aclrtDestroyContext(context)) {
        throw std::runtime_error("aclrtDestroyContext");
    }
    if (ACL_SUCCESS != aclrtResetDevice(device)) {
        throw std::runtime_error("aclrtResetDevice");
    }
    if (ACL_SUCCESS != aclFinalize()) {
        throw std::runtime_error("aclFinalize");
    }
    int64_t timestamp = 0, size = 0;
    Write(STDOUT_FILENO, &timestamp);
    Write(STDOUT_FILENO, &size);
    return 0;
}
