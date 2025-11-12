import common.Utils.cPointer
import common.Utils.check
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import platform.acl.aclrtCreateContext
import platform.acl.aclrtCreateStream
import platform.acl.aclrtDestroyContext
import platform.acl.aclrtDestroyStream
import platform.acl.aclrtGetRunMode
import platform.acl.aclrtMemcpyKind
import platform.acl.aclrtProcessReport
import platform.acl.aclrtResetDevice
import platform.acl.aclrtRunMode
import platform.acl.aclrtSetCurrentContext
import platform.acl.aclrtSetDevice

@OptIn(ExperimentalForeignApi::class)
class SessionACL(val device: Int) : AutoCloseable {
    val context: CPointer<CPointed> = cPointer {
        aclrtCreateContext(it.reinterpret(), device).check("aclrtCreateContext")
    }

    val stream: CPointer<CPointed> = cPointer {
        aclrtCreateStream(it.reinterpret()).check("aclrtCreateStream")
    }

    val runMode: aclrtRunMode = memScoped {
        alloc<aclrtRunMode.Var>().also { aclrtGetRunMode(it.ptr).check("aclrtGetRunMode") }.value
    }

    fun setContext() {
        aclrtSetDevice(device).check("aclrtSetDevice")
        aclrtSetCurrentContext(context).check("aclrtSetCurrentContext")
    }

    fun process(timeout: Int) {
        aclrtProcessReport(timeout).check("aclrtProcessReport")
    }

    override fun close() {
        aclrtDestroyStream(stream).check("aclrtDestroyStream")
        aclrtDestroyContext(context).check("aclrtDestroyContext")
        aclrtResetDevice(device).check("aclrtResetDevice")
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
