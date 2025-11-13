import common.Utils.cPointer
import common.Utils.checkEq0
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.acl.*
import platform.posix.pthread_self

@OptIn(ExperimentalForeignApi::class)
class SessionACL(val device: Int) : AutoCloseable {
    var threadId = 0UL

    val context: CPointer<CPointed> = cPointer {
        aclrtCreateContext(it.reinterpret(), device).checkEq0("aclrtCreateContext")
    }

    val runMode: aclrtRunMode = memScoped {
        alloc<aclrtRunMode.Var>().also { aclrtGetRunMode(it.ptr).checkEq0("aclrtGetRunMode") }.value
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    val dispatcher = newSingleThreadContext("ACLCallbackThread")

    val job = CoroutineScope(dispatcher).launch {
        threadId = pthread_self()
        setContext()
        aclrtSubscribeReport(threadId, null)
        while (true) {
            aclrtProcessReport(-1).checkEq0("aclrtProcessReport")
            delay(0)
        }
    }

    fun setContext() {
        aclrtSetDevice(device).checkEq0("aclrtSetDevice")
        aclrtSetCurrentContext(context).checkEq0("aclrtSetCurrentContext")
    }

    override fun close() {
        runBlocking { job.cancelAndJoin() }
        dispatcher.close()
        aclrtDestroyContext(context).checkEq0("aclrtDestroyContext")
        aclrtResetDevice(device).checkEq0("aclrtResetDevice")
    }

    fun uploadMode() = when (runMode) {
        aclrtRunMode.ACL_HOST -> aclrtMemcpyKind.ACL_MEMCPY_HOST_TO_DEVICE
        else -> aclrtMemcpyKind.ACL_MEMCPY_DEVICE_TO_DEVICE
    }

    fun downloadMode() = when (runMode) {
        aclrtRunMode.ACL_HOST -> aclrtMemcpyKind.ACL_MEMCPY_DEVICE_TO_HOST
        else -> aclrtMemcpyKind.ACL_MEMCPY_DEVICE_TO_DEVICE
    }
}
