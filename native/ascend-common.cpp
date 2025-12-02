#include "ascend-common.h"

aclrtMemcpyKind up, down;
int width, height, wstride, hstride;
QObject aclWorker, callbackWorker;
acldvppStreamFormat codecType;
int device, id, bitRate;
QThread aclThread, callbackThread;
aclrtContext context;

void Process() {
    aclrtProcessReport(1000);
    QMetaObject::invokeMethod(
        &callbackWorker, [] { Process(); }, Qt::QueuedConnection);
}

pthread_t ParseArgsAndInit(char** argv) {
    device = strtol(argv[1], nullptr, 10);
    id = strtol(argv[2], nullptr, 10);
    int bitRate;
    if (strcmp(argv[3], "h264") == 0) {
        codecType = H264_HIGH_LEVEL;
        bitRate = 1500;
    }
    if (strcmp(argv[3], "hevc") == 0) {
        codecType = H265_MAIN_LEVEL;
        bitRate = 750;
    }
    width = strtol(argv[4], nullptr, 10);
    height = strtol(argv[5], nullptr, 10);
    wstride = (width + 15) / 16 * 16;
    hstride = (height + 1) / 2 * 2;
    ACLCHECK(aclInit(nullptr));
    ACLCHECK(aclrtCreateContext(&context, device));
    ACLCHECK(aclrtSetDevice(device));
    ACLCHECK(aclrtSetCurrentContext(context));
    aclrtRunMode runMode;
    ACLCHECK(aclrtGetRunMode(&runMode));
    if (runMode == ACL_HOST) {
        up = ACL_MEMCPY_HOST_TO_DEVICE;
        down = ACL_MEMCPY_DEVICE_TO_HOST;
    } else {
        up = ACL_MEMCPY_DEVICE_TO_DEVICE;
        down = ACL_MEMCPY_DEVICE_TO_DEVICE;
    }
    aclWorker.moveToThread(&aclThread);
    aclThread.start();
    QMetaObject::invokeMethod(
        &aclWorker,
        [&] {
            ACLCHECK(aclrtSetDevice(device));
            ACLCHECK(aclrtSetCurrentContext(context));
        },
        Qt::BlockingQueuedConnection);
    QThread;
    callbackWorker.moveToThread(&callbackThread);
    callbackThread.start();
    pthread_t callbackPid;
    QMetaObject::invokeMethod(
        &callbackWorker,
        [&] {
            callbackPid = pthread_self();
            ACLCHECK(aclrtSetDevice(device));
            ACLCHECK(aclrtSetCurrentContext(context));
        },
        Qt::BlockingQueuedConnection);
    return callbackPid;
}

void Finalize() {
    ACLCHECK(aclrtDestroyContext(context));
    ACLCHECK(aclrtResetDevice(device));
    ACLCHECK(aclFinalize());
}