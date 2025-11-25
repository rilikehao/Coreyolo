#include <acl/acl.h>
#include <acl/ops/acl_dvpp.h>

#include <QThread>
#include <stdexcept>

using namespace std;

aclrtMemcpyKind upload, download;

int main(int argc, char** argv) {
    int device = strtol(argv[1], nullptr, 10);
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
    QObject callbackWorker;
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
