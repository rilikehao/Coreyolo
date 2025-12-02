#ifndef ASCEND_CHECK
#define ASCEND_CHECK

#include <acl/acl.h>
#include <acl/ops/acl_dvpp.h>

#include <QObject>
#include <QThread>
#include <stdexcept>
#include <string>

#define ACLCHECK(f)                       \
    {                                     \
        aclError error = f;               \
        if (ACL_SUCCESS != error) {       \
            std::string e(#f);            \
            e += " = ";                   \
            e += std::to_string(error);   \
            throw std::runtime_error(#f); \
        }                                 \
    }

extern aclrtMemcpyKind up, down;
extern int width, height, wstride, hstride;
extern aclvencChannelDesc* channel;
extern QObject aclWorker, callbackWorker;
extern acldvppStreamFormat codecType;
extern int device, id, bitRate;
extern QThread aclThread, callbackThread;
extern aclrtContext context;

void Process();
pthread_t ParseArgsAndInit(char** argv);
void Finalize();

#endif  // ASCEND_CHECK