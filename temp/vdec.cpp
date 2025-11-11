#include <cstdio>
#include <cstdlib>
#include <string>
#include <sys/stat.h>
#include "acl/acl.h"
#include "acl/ops/acl_dvpp.h"

namespace {
constexpr auto kConfigPath = "vdec/src/acl.json";
constexpr auto kStreamPath = "vdec/data/vdec_h265_1frame_rabbit_1280x720.h265";
constexpr int kWidth = 1280;
constexpr int kHeight = 720;
constexpr int kChannelId = 0;
constexpr acldvppStreamFormat kCodec = ACL_VIDEO_H265_MAIN_LEVEL;
constexpr acldvppPixelFormat kPixel = PIXEL_FORMAT_YUV_SEMIPLANAR_420;
aclrtRunMode g_runMode;

bool LoadStream(const char *path, void *&dev, uint32_t &size) {
    FILE *fp = std::fopen(path, "rb");
    if (!fp) return false;
    std::fseek(fp, 0, SEEK_END);
    long len = std::ftell(fp);
    std::fseek(fp, 0, SEEK_SET);
    size = static_cast<uint32_t>(len);
    void *host = std::malloc(size);
    if (!host) {
        std::fclose(fp);
        return false;
    }
    if (std::fread(host, 1, size, fp) != size) {
        std::free(host);
        std::fclose(fp);
        return false;
    }
    std::fclose(fp);
    if (acldvppMalloc(&dev, size) != ACL_SUCCESS) {
        std::free(host);
        return false;
    }
    aclError ret = aclrtMemcpy(dev, size, host, size,
        g_runMode == ACL_HOST ? ACL_MEMCPY_HOST_TO_DEVICE : ACL_MEMCPY_DEVICE_TO_DEVICE);
    std::free(host);
    return ret == ACL_SUCCESS;
}

bool DumpFrame(const char *path, const void *dev, uint32_t size) {
    void *host = std::malloc(size);
    if (!host) return false;
    aclError ret = aclrtMemcpy(host, size, dev, size,
        g_runMode == ACL_HOST ? ACL_MEMCPY_DEVICE_TO_HOST : ACL_MEMCPY_DEVICE_TO_DEVICE);
    if (ret != ACL_SUCCESS) {
        std::free(host);
        return false;
    }
    FILE *fp = std::fopen(path, "wb");
    if (!fp) {
        std::free(host);
        return false;
    }
    bool ok = std::fwrite(host, 1, size, fp) == size;
    std::fclose(fp);
    std::free(host);
    return ok;
}

void Callback(acldvppStreamDesc *input, acldvppPicDesc *output, void *userdata) {
    (void)input;
    (void)userdata;
    static int index = 0;
    mkdir("output", 0755);
    std::string path = "output/frame" + std::to_string(index++) + ".yuv";
    void *data = acldvppGetPicDescData(output);
    uint32_t size = acldvppGetPicDescSize(output);
    DumpFrame(path.c_str(), data, size);
    acldvppFree(data);
    acldvppDestroyPicDesc(output);
}
}

int main() {
    if (aclInit(kConfigPath) != ACL_SUCCESS) return EXIT_FAILURE;
    int32_t device = 0;
    if (aclrtSetDevice(device) != ACL_SUCCESS) return EXIT_FAILURE;
    aclrtContext context = nullptr;
    aclrtStream stream = nullptr;
    if (aclrtCreateContext(&context, device) != ACL_SUCCESS) return EXIT_FAILURE;
    if (aclrtCreateStream(&stream) != ACL_SUCCESS) return EXIT_FAILURE;
    aclrtGetRunMode(&g_runMode);

    aclvdecChannelDesc *channel = aclvdecCreateChannelDesc();
    aclvdecSetChannelDescChannelId(channel, kChannelId);
    aclvdecSetChannelDescCallback(channel, Callback);
    aclvdecSetChannelDescEnType(channel, kCodec);
    aclvdecSetChannelDescOutPicFormat(channel, kPixel);
    aclvdecCreateChannel(channel);

    void *streamDev = nullptr;
    uint32_t streamSize = 0;
    if (!LoadStream(kStreamPath, streamDev, streamSize)) return EXIT_FAILURE;

    size_t frameSize = static_cast<size_t>(kWidth) * kHeight * 3 / 2;
    acldvppStreamDesc *streamDesc = acldvppCreateStreamDesc();
    acldvppSetStreamDescData(streamDesc, streamDev);
    acldvppSetStreamDescSize(streamDesc, streamSize);

    void *frameDev = nullptr;
    acldvppMalloc(&frameDev, frameSize);
    acldvppPicDesc *picDesc = acldvppCreatePicDesc();
    acldvppSetPicDescData(picDesc, frameDev);
    acldvppSetPicDescSize(picDesc, frameSize);
    acldvppSetPicDescFormat(picDesc, kPixel);

    aclvdecSendFrame(channel, streamDesc, picDesc, nullptr, nullptr);
    aclrtProcessReport(1000);

    acldvppDestroyStreamDesc(streamDesc);
    acldvppDestroyChannel(channel);
    aclvdecDestroyChannelDesc(channel);
    acldvppFree(streamDev);

    aclrtDestroyStream(stream);
    aclrtDestroyContext(context);
    aclrtResetDevice(device);
    aclFinalize();
    return EXIT_SUCCESS;
}
