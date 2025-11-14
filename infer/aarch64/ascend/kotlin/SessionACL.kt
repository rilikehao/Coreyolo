import common.Utils.cPointer
import common.Utils.checkEq0
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.acl.*
import platform.posix.pthread_self

@OptIn(ExperimentalForeignApi::class)
class SessionACL(val device: Int) : AutoCloseable {
    val threadId = CompletableDeferred<ULong>()
    val channelReady = CompletableDeferred<Unit>()

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    val main = newSingleThreadContext("ACLMain")

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    val callback = newSingleThreadContext("ACLCallback")

    val job = CoroutineScope(callback).launch {
        threadId.complete(pthread_self())
        channelReady.await()
        setContext()
        while (true) {
            aclrtProcessReport(-1).checkEq0("aclrtProcessReport")
            delay(0)
        }
    }

    val context: CPointer<CPointed> = cPointer {
        aclrtCreateContext(it.reinterpret(), device).checkEq0("aclrtCreateContext")
    }

    fun setContext() {
        aclrtSetDevice(device).checkEq0("aclrtSetDevice")
        aclrtSetCurrentContext(context).checkEq0("aclrtSetCurrentContext")
    }

    val runMode: aclrtRunMode = memScoped {
        alloc<aclrtRunMode.Var>().also { aclrtGetRunMode(it.ptr).checkEq0("aclrtGetRunMode") }.value
    }

    override fun close() {
        runBlocking { job.cancelAndJoin() }
        callback.close()
        main.close()
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
