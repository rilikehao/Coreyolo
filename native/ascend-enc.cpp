#include <acl/acl.h>
#include <acl/ops/acl_dvpp.h>

#include <QCoreApplication>
#include <QDebug>
#include <QFile>
#include <QThread>
#include <stdexcept>

#include "ascend-common.h"
#include "io.h"

aclvencChannelDesc* channel;

struct Data {
    int64_t timestamp_;
    bool is_key_frame_;
};

void Callback(acldvppPicDesc* input, acldvppStreamDesc* output,
              void* rawData) {
    auto data = static_cast<Data*>(rawData);
    QByteArray o;
    int64_t oSize;
    QMetaObject::invokeMethod(
        &aclWorker,
        [&] {
            ACLCHECK(acldvppFree(acldvppGetPicDescData(input)));
            ACLCHECK(acldvppDestroyPicDesc(input));
            auto oDev = acldvppGetStreamDescData(output);
            oSize = acldvppGetStreamDescSize(output);
            o.resizeForOverwrite(oSize);
            ACLCHECK(aclrtMemcpy(o.data(), oSize, oDev, oSize, down));
        },
        Qt::BlockingQueuedConnection);
    Write(STDOUT_FILENO, &data->timestamp_);
    Write(STDOUT_FILENO, &data->is_key_frame_);
    Write(STDOUT_FILENO, &oSize);
    Write(STDOUT_FILENO, o.data(), oSize);
    delete data;
}

void Work(pthread_t callbackPid) {
    channel = aclvencCreateChannelDesc();
    if (!channel) throw std::runtime_error("aclvencCreateChannelDesc");
    ACLCHECK(aclvencSetChannelDescThreadId(channel, callbackPid));
    ACLCHECK(aclvencSetChannelDescCallback(channel, Callback));
    ACLCHECK(aclvencSetChannelDescEnType(channel, codecType));
    auto format = PIXEL_FORMAT_YUV_SEMIPLANAR_420;
    ACLCHECK(aclvencSetChannelDescPicFormat(channel, format));
    ACLCHECK(aclvencSetChannelDescPicWidth(channel, width));
    ACLCHECK(aclvencSetChannelDescPicHeight(channel, height));
    ACLCHECK(aclvencSetChannelDescKeyFrameInterval(channel, 65536));
    ACLCHECK(aclvencSetChannelDescMaxBitRate(channel, bitRate));
    ACLCHECK(aclvencSetChannelDescRcMode(channel, 1));
    ACLCHECK(aclvencCreateChannel(channel));
    QMetaObject::invokeMethod(
        &callbackWorker, [&] { Process(); }, Qt::QueuedConnection);
    while (true) {
        auto data = new Data;
        QByteArray i;
        int64_t iSize;
        Read(STDIN_FILENO, &data->timestamp_);
        Read(STDIN_FILENO, &data->is_key_frame_);
        Read(STDIN_FILENO, &iSize);
        i.resizeForOverwrite(iSize);
        Read(STDIN_FILENO, i.data(), iSize);
        if (data->timestamp_ == 0) break;
        acldvppPicDesc* iDesc;
        QMetaObject::invokeMethod(
            &aclWorker,
            [&] {
                void* iDev;
                ACLCHECK(acldvppMalloc(&iDev, iSize));
                ACLCHECK(aclrtMemcpy(iDev, iSize, i.data(), iSize, up));
                iDesc = acldvppCreatePicDesc();
                ACLCHECK(acldvppSetPicDescData(iDesc, iDev));
                ACLCHECK(acldvppSetPicDescSize(iDesc, iSize));
                ACLCHECK(acldvppSetPicDescFormat(iDesc, format));
                ACLCHECK(acldvppSetPicDescWidth(iDesc, width));
                ACLCHECK(acldvppSetPicDescHeight(iDesc, height));
                ACLCHECK(acldvppSetPicDescWidthStride(iDesc, wstride));
                ACLCHECK(acldvppSetPicDescHeightStride(iDesc, hstride));
            },
            Qt::BlockingQueuedConnection);
        auto config = aclvencCreateFrameConfig();
        aclvencSetFrameConfigEos(config, 0);
        aclvencSetFrameConfigForceIFrame(config, data->is_key_frame_);
        aclvencSendFrame(channel, iDesc, 0, config, data);
        aclvencDestroyFrameConfig(config);
    }
    QMetaObject::invokeMethod(
        &aclWorker,
        [&] {
            auto config = aclvencCreateFrameConfig();
            ACLCHECK(aclvencSetFrameConfigEos(config, 1));
            aclvencSendFrame(channel, 0, 0, config, 0);
            aclvencDestroyFrameConfig(config);
            QCoreApplication::instance()->quit();
        },
        Qt::BlockingQueuedConnection);
    aclThread.quit();
    aclThread.wait();
    callbackThread.quit();
    callbackThread.wait();
    ACLCHECK(aclvencDestroyChannel(channel));
    ACLCHECK(aclvencDestroyChannelDesc(channel));
}

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    auto callbackPid = ParseArgsAndInit(argv);
    Work(callbackPid);
    Finalize();
    int64_t timestamp = 0, size = 0;
    bool isKeyFrame = false;
    Write(STDOUT_FILENO, &timestamp);
    Write(STDOUT_FILENO, &isKeyFrame);
    Write(STDOUT_FILENO, &size);
    return 0;
}
