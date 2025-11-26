#define ENABLE_DVPP_INTERFACE

#include <acl/acl.h>
#include <acl/ops/acl_dvpp.h>

#include <QCoreApplication>
#include <QDataStream>
#include <QDebug>
#include <QFile>
#include <QThread>
#include <stdexcept>

aclrtMemcpyKind upload, download;
int width, height, wstride, hstride;
aclvdecChannelDesc* channel;

QFile inFile, outFile;
std::unique_ptr<QDataStream> in, out;

QObject worker, callbackWorker;

void process() {
    qDebug() << "callback";
    if (ACL_SUCCESS != aclrtProcessReport(-1)) {
        throw std::runtime_error("aclrtProcessReport");
    }
    QMetaObject::invokeMethod(
        &callbackWorker, [] { process(); }, Qt::QueuedConnection);
}

void callback(acldvppStreamDesc* input, acldvppPicDesc* output,
              void* data) {
    qDebug() << "run callback";
    if (!data) {
        (*out) << qint64(0);
        out->writeBytes(nullptr, 0);
    } else {
        auto streamDev = acldvppGetStreamDescData(input);
        if (ACL_SUCCESS != acldvppFree(streamDev)) {
            throw std::runtime_error("acldvppFree");
        }
        if (ACL_SUCCESS != acldvppDestroyStreamDesc(input)) {
            throw std::runtime_error("acldvppFree");
        }
        auto picDev = acldvppGetPicDescData(output);
        auto picSize = acldvppGetPicDescSize(output);
        QByteArray pic(picSize, Qt::Initialization::Uninitialized);
        if (ACL_SUCCESS !=  //
            aclrtMemcpy(
                pic.data(), picSize, picDev, picSize, download)) {
            throw std::runtime_error("aclrtMemcpy");
        }
        if (ACL_SUCCESS != acldvppFree(picDev)) {
            throw std::runtime_error("acldvppFree");
        }
        if (ACL_SUCCESS != acldvppDestroyPicDesc(output)) {
            throw std::runtime_error("acldvppFree");
        }
        auto timestamp = static_cast<qint64*>(data);
        qDebug() << "pre-put" << (*timestamp);
        (*out) << (*timestamp);
        out->writeBytes(pic.constData(), pic.size());
        qDebug() << "put" << (*timestamp);
        delete timestamp;
    }
}

void preprocess() {
    auto timestamp = new qint64;
    char* stream;
    qint64 streamSize;
    qDebug() << "pre-start";
    qDebug() << "post-start";
    (*in) >> (*timestamp);
    if (*timestamp == 0) {
        QMetaObject::invokeMethod(
            &worker,
            [&] {
                auto streamDesc = acldvppCreateStreamDesc();
                if (!streamDesc) {
                    throw std::runtime_error("acldvppCreateStreamDesc");
                }
                if (ACL_SUCCESS !=
                    acldvppSetStreamDescEos(streamDesc, 1)) {
                    throw std::runtime_error("acldvppSetStreamDescEos");
                }
                if (ACL_SUCCESS !=  //
                    aclvdecSendFrame(channel, streamDesc, nullptr,
                                     nullptr, nullptr)) {
                    throw std::runtime_error("aclvdecSendFrame");
                }
                if (ACL_SUCCESS != aclvdecDestroyChannel(channel)) {
                    throw std::runtime_error("aclvdecDestroyChannel");
                }
                if (ACL_SUCCESS != aclvdecDestroyChannelDesc(channel)) {
                    throw std::runtime_error(
                        "aclvdecDestroyChannelDesc");
                }
                QCoreApplication::instance()->quit();
            },
            Qt::QueuedConnection);
        return;
    }
    qDebug() << *timestamp;
    in->readBytes(stream, streamSize);
    qDebug() << "get" << *timestamp;
    void *streamDev, *picDev;
    if (ACL_SUCCESS != acldvppMalloc(&streamDev, streamSize)) {
        throw std::runtime_error("acldvppMalloc");
    }
    if (ACL_SUCCESS !=                      //
        aclrtMemcpy(streamDev, streamSize,  //
                    stream, streamSize, upload)) {
        throw std::runtime_error("aclrtMemcpy");
    }
    auto streamDesc = acldvppCreateStreamDesc();
    if (!streamDesc) {
        throw std::runtime_error("acldvppCreateStreamDesc");
    }
    if (ACL_SUCCESS !=
        acldvppSetStreamDescData(streamDesc, streamDev)) {
        throw std::runtime_error("acldvppSetStreamDescData");
    }
    if (ACL_SUCCESS !=
        acldvppSetStreamDescSize(streamDesc, streamSize)) {
        throw std::runtime_error("acldvppSetStreamDescSize");
    }
    int picSize = wstride * hstride / 2 * 3;  // NV12
    if (ACL_SUCCESS != acldvppMalloc(&picDev, picSize)) {
        throw std::runtime_error("acldvppMalloc");
    }
    auto picDesc = acldvppCreatePicDesc();
    if (!picDesc) {
        throw std::runtime_error("acldvppCreatePicDesc");
    }
    if (ACL_SUCCESS != acldvppSetPicDescData(picDesc, picDev)) {
        throw std::runtime_error("acldvppSetPicDescData");
    }
    if (ACL_SUCCESS != acldvppSetPicDescSize(picDesc, picSize)) {
        throw std::runtime_error("acldvppSetPicDescSize");
    }
    if (ACL_SUCCESS != acldvppSetPicDescFormat(
                           picDesc, PIXEL_FORMAT_YUV_SEMIPLANAR_420)) {
        throw std::runtime_error("acldvppSetPicDescFormat");
    }
    if (ACL_SUCCESS != acldvppSetPicDescWidth(picDesc, width)) {
        throw std::runtime_error("acldvppSetPicDescWidth");
    }
    if (ACL_SUCCESS != acldvppSetPicDescHeight(picDesc, height)) {
        throw std::runtime_error("acldvppSetPicDescHeight");
    }
    if (ACL_SUCCESS != acldvppSetPicDescWidthStride(picDesc, wstride)) {
        throw std::runtime_error("acldvppSetPicDescWidthStride");
    }
    if (ACL_SUCCESS !=
        acldvppSetPicDescHeightStride(picDesc, hstride)) {
        throw std::runtime_error("acldvppSetPicDescHeightStride");
    }
    qDebug() << "pre-send";
    if (ACL_SUCCESS !=  //
        aclvdecSendFrame(
            channel, streamDesc, picDesc, nullptr, timestamp)) {
        throw std::runtime_error("aclvdecSendFrame");
    }
    qDebug() << "post-send";
    delete[] stream;
    QMetaObject::invokeMethod(
        &worker, [] { preprocess(); }, Qt::QueuedConnection);
}

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    int device = strtol(argv[1], nullptr, 10);
    acldvppStreamFormat decodeType;
    if (strcmp(argv[2], "h264") == 0) decodeType = H264_HIGH_LEVEL;
    if (strcmp(argv[2], "hevc") == 0) decodeType = H265_MAIN_LEVEL;
    width = strtol(argv[3], nullptr, 10);
    height = strtol(argv[4], nullptr, 10);
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
    if (ACL_SUCCESS != aclvdecSetChannelDescChannelId(channel, 0)) {
        throw std::runtime_error("aclvdecSetChannelDescChannelId");
    }
    if (ACL_SUCCESS !=
        aclvdecSetChannelDescThreadId(channel, callbackPid)) {
        throw std::runtime_error("aclvdecSetChannelDescThreadId");
    }
    if (ACL_SUCCESS !=
        aclvdecSetChannelDescCallback(channel, callback)) {
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
        &callbackWorker, [&] { process(); }, Qt::QueuedConnection);
    if (!inFile.open(stdin, QIODevice::ReadOnly)) {
        throw std::runtime_error("inFile.open");
    }
    if (!outFile.open(stdout, QIODevice::WriteOnly)) {
        throw std::runtime_error("outFile.open");
    }
    in = std::make_unique<QDataStream>(&inFile);
    out = std::make_unique<QDataStream>(&outFile);
    QMetaObject::invokeMethod(
        &worker, [] { preprocess(); }, Qt::QueuedConnection);
    app.exec();
    callbackThread.quit();
    callbackThread.wait();
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
