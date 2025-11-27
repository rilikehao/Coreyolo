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
aclvencChannelDesc* channel;

QObject aclWorker, callbackWorker;

void Process() {
    aclrtProcessReport(1000);
    QMetaObject::invokeMethod(
        &callbackWorker, [] { Process(); }, Qt::QueuedConnection);
}

struct Data {
    int64_t timestamp_;
    bool is_key_frame_;
};

void Callback(acldvppPicDesc* input, acldvppStreamDesc* output,
              void* rawData) {
    auto data = static_cast<Data*>(rawData);
    if (!data) {
        int64_t timestamp = 0, size = 0;
        bool isKeyFrame = false;
        Write(STDOUT_FILENO, &timestamp);
        Write(STDOUT_FILENO, &isKeyFrame);
        Write(STDOUT_FILENO, &size);
    } else {
        QByteArray encodedStream;
        int64_t streamSize;
        QMetaObject::invokeMethod(
            &aclWorker,
            [&] {
                auto picDev = acldvppGetPicDescData(input);
                if (ACL_SUCCESS != acldvppFree(picDev)) {
                    throw std::runtime_error("acldvppFree");
                }
                if (ACL_SUCCESS != acldvppDestroyPicDesc(input)) {
                    throw std::runtime_error("acldvppDestroyPicDesc");
                }
                auto streamDev = acldvppGetStreamDescData(output);
                streamSize = acldvppGetStreamDescSize(output);
                encodedStream.resizeForOverwrite(streamSize);
                if (ACL_SUCCESS !=  //
                    aclrtMemcpy(encodedStream.data(), streamSize,
                                streamDev, streamSize, download)) {
                    throw std::runtime_error(
                        "aclrtMemcpy encoded stream");
                }
            },
            Qt::BlockingQueuedConnection);
        Write(STDOUT_FILENO, &data->timestamp_);
        Write(STDOUT_FILENO, &data->is_key_frame_);
        Write(STDOUT_FILENO, &streamSize);
        Write(STDOUT_FILENO, encodedStream.data(), streamSize);
        delete data;
    }
}

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    int device = strtol(argv[1], nullptr, 10);
    int id = strtol(argv[2], nullptr, 10);
    acldvppStreamFormat encodeType;
    int bitRate;
    if (strcmp(argv[3], "h264") == 0) {
        encodeType = H264_HIGH_LEVEL;
        bitRate = 1500;
    }
    if (strcmp(argv[3], "hevc") == 0) {
        encodeType = H265_MAIN_LEVEL;
        bitRate = 750;
    }
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

    channel = aclvencCreateChannelDesc();
    if (!channel) throw std::runtime_error("aclvencCreateChannelDesc");
    if (ACL_SUCCESS !=
        aclvencSetChannelDescThreadId(channel, callbackPid)) {
        throw std::runtime_error("aclvencSetChannelDescThreadId");
    }
    if (ACL_SUCCESS !=
        aclvencSetChannelDescCallback(channel, Callback)) {
        throw std::runtime_error("aclvencSetChannelDescCallback");
    }
    if (ACL_SUCCESS !=
        aclvencSetChannelDescEnType(channel, encodeType)) {
        throw std::runtime_error("aclvencSetChannelDescEnType");
    }
    if (ACL_SUCCESS !=  //
        aclvencSetChannelDescPicFormat(
            channel, PIXEL_FORMAT_YUV_SEMIPLANAR_420)) {
        throw std::runtime_error("aclvencSetChannelDescPicFormat");
    }
    if (ACL_SUCCESS != aclvencSetChannelDescPicWidth(channel, width)) {
        throw std::runtime_error("aclvencSetChannelDescPicWidth");
    }
    if (ACL_SUCCESS !=
        aclvencSetChannelDescPicHeight(channel, height)) {
        throw std::runtime_error("aclvencSetChannelDescPicHeight");
    }
    if (ACL_SUCCESS !=
        aclvencSetChannelDescKeyFrameInterval(channel, 65536)) {
        throw std::runtime_error(
            "aclvencSetChannelDescKeyFrameInterval");
    }
    if (ACL_SUCCESS !=
        aclvencSetChannelDescMaxBitRate(channel, bitRate)) {
        throw std::runtime_error("aclvencSetChannelDescMaxBitRate");
    }
    if (ACL_SUCCESS != aclvencSetChannelDescRcMode(channel, 1)) {
        throw std::runtime_error("aclvencSetChannelDescRcMode");
    }
    if (ACL_SUCCESS != aclvencCreateChannel(channel)) {
        throw std::runtime_error("aclvencCreateChannel");
    }

    QMetaObject::invokeMethod(
        &callbackWorker, [&] { Process(); }, Qt::QueuedConnection);

    int picSize = wstride * hstride / 2 * 3;  // NV12

    while (true) {
        auto data = new Data;
        QByteArray frame;
        int64_t size;
        Read(STDIN_FILENO, &data->timestamp_);
        Read(STDIN_FILENO, &data->is_key_frame_);
        Read(STDIN_FILENO, &size);
        frame.resizeForOverwrite(size);
        Read(STDIN_FILENO, frame.data(), size);

        if (data->timestamp_ == 0) break;

        acldvppPicDesc* picDesc;
        QMetaObject::invokeMethod(
            &aclWorker,
            [&] {
                void* picDev;
                if (ACL_SUCCESS != acldvppMalloc(&picDev, picSize)) {
                    throw std::runtime_error("acldvppMalloc");
                }
                if (ACL_SUCCESS !=                //
                    aclrtMemcpy(picDev, picSize,  //
                                frame.data(), frame.size(), upload)) {
                    throw std::runtime_error("aclrtMemcpy frame");
                }
                picDesc = acldvppCreatePicDesc();
                if (!picDesc) {
                    throw std::runtime_error("acldvppCreatePicDesc");
                }
                if (ACL_SUCCESS !=
                    acldvppSetPicDescData(picDesc, picDev)) {
                    throw std::runtime_error("acldvppSetPicDescData");
                }
                if (ACL_SUCCESS !=  //
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

        auto config = aclvencCreateFrameConfig();
        if (!config)
            throw std::runtime_error("aclvencCreateFrameConfig");
        aclvencSetFrameConfigEos(config, 0);
        aclvencSetFrameConfigForceIFrame(config, data->is_key_frame_);
        if (ACL_SUCCESS !=  //
            aclvencSendFrame(channel, picDesc, nullptr, config, data)) {
            qDebug() << "aclvencSendFrame";
        }
        aclvencDestroyFrameConfig(config);
    }

    QMetaObject::invokeMethod(
        &aclWorker,
        [&] {
            auto config = aclvencCreateFrameConfig();
            if (!config) {
                throw std::runtime_error("aclvencCreateFrameConfig");
            }
            if (ACL_SUCCESS != aclvencSetFrameConfigEos(config, 1)) {
                throw std::runtime_error("aclvencSetFrameConfigEos");
            }
            if (ACL_SUCCESS !=  //
                aclvencSendFrame(
                    channel, nullptr, nullptr, config, nullptr)) {
                throw std::runtime_error("aclvencSendFrame EOS");
            }
            aclvencDestroyFrameConfig(config);
            QCoreApplication::instance()->quit();
        },
        Qt::BlockingQueuedConnection);
    aclThread.quit();
    aclThread.wait();
    callbackThread.quit();
    callbackThread.wait();
    if (ACL_SUCCESS != aclvencDestroyChannel(channel)) {
        throw std::runtime_error("aclvencDestroyChannel");
    }
    if (ACL_SUCCESS != aclvencDestroyChannelDesc(channel)) {
        throw std::runtime_error("aclvencDestroyChannelDesc");
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
    return 0;
}