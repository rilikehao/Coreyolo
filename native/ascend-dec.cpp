#include <acl/acl.h>
#include <acl/ops/acl_dvpp.h>

#include <QCoreApplication>
#include <QDebug>
#include <QFile>
#include <QThread>
#include <stdexcept>

#include "ascend-common.h"
#include "io.h"

aclvdecChannelDesc* channel;

void Callback(acldvppStreamDesc* input, acldvppPicDesc* output,
              void* data) {
    QByteArray pic;
    QMetaObject::invokeMethod(
        &aclWorker,
        [&] {
            auto iDev = acldvppGetStreamDescData(input);
            ACLCHECK(acldvppFree(iDev));
            ACLCHECK(acldvppDestroyStreamDesc(input));
            auto oDev = acldvppGetPicDescData(output);
            auto size = acldvppGetPicDescSize(output);
            pic.resizeForOverwrite(size);
            ACLCHECK(aclrtMemcpy(pic.data(), size, oDev, size, down));
            ACLCHECK(acldvppFree(oDev));
            ACLCHECK(acldvppDestroyPicDesc(output));
        },
        Qt::BlockingQueuedConnection);
    auto timestamp = static_cast<qint64*>(data);
    int64_t size = pic.size();
    Write(STDOUT_FILENO, timestamp);
    Write(STDOUT_FILENO, &size);
    Write(STDOUT_FILENO, pic.data(), size);
    delete timestamp;
}

void Work(pthread_t callbackPid) {
    channel = aclvdecCreateChannelDesc();
    if (!channel) throw std::runtime_error("aclvdecCreateChannelDesc");
    ACLCHECK(aclvdecSetChannelDescChannelId(channel, id));
    ACLCHECK(aclvdecSetChannelDescThreadId(channel, callbackPid));
    ACLCHECK(aclvdecSetChannelDescCallback(channel, Callback));
    ACLCHECK(aclvdecSetChannelDescEnType(channel, codecType));
    auto format = PIXEL_FORMAT_YUV_SEMIPLANAR_420;
    ACLCHECK(aclvdecSetChannelDescOutPicFormat(channel, format));
    ACLCHECK(aclvdecCreateChannel(channel));
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
        acldvppStreamDesc* iDesc;
        acldvppPicDesc* oDesc;
        QMetaObject::invokeMethod(
            &aclWorker,
            [&] {
                void *iDev, *oDev, *iData = stream.data();
                int iSize = stream.size();
                int oSize = wstride * hstride / 2 * 3;  // NV12
                ACLCHECK(acldvppMalloc(&iDev, iSize));
                ACLCHECK(aclrtMemcpy(iDev, iSize, iData, iSize, up));
                iDesc = acldvppCreateStreamDesc();
                ACLCHECK(acldvppSetStreamDescData(iDesc, iDev));
                ACLCHECK(acldvppSetStreamDescSize(iDesc, iSize));
                ACLCHECK(acldvppMalloc(&oDev, oSize));
                oDesc = acldvppCreatePicDesc();
                ACLCHECK(acldvppSetPicDescData(oDesc, oDev));
                ACLCHECK(acldvppSetPicDescSize(oDesc, oSize));
                ACLCHECK(acldvppSetPicDescFormat(oDesc, format));
                ACLCHECK(acldvppSetPicDescWidth(oDesc, width));
                ACLCHECK(acldvppSetPicDescHeight(oDesc, height));
                ACLCHECK(acldvppSetPicDescWidthStride(oDesc, wstride));
                ACLCHECK(acldvppSetPicDescHeightStride(oDesc, hstride));
            },
            Qt::BlockingQueuedConnection);
        aclvdecSendFrame(channel, iDesc, oDesc, 0, timestamp);
    }
    QMetaObject::invokeMethod(
        &aclWorker,
        [&] {
            auto iDesc = acldvppCreateStreamDesc();
            ACLCHECK(acldvppSetStreamDescEos(iDesc, 1));
            aclvdecSendFrame(channel, iDesc, 0, 0, 0);
            QCoreApplication::instance()->quit();
        },
        Qt::BlockingQueuedConnection);
    aclThread.quit();
    aclThread.wait();
    callbackThread.quit();
    callbackThread.wait();
    ACLCHECK(aclvdecDestroyChannel(channel));
    ACLCHECK(aclvdecDestroyChannelDesc(channel));
}

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    auto callbackPid = ParseArgsAndInit(argv);
    Work(callbackPid);
    Finalize();
    int64_t timestamp = 0, size = 0;
    Write(STDOUT_FILENO, &timestamp);
    Write(STDOUT_FILENO, &size);
    return 0;
}
