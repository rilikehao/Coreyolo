import common.Utils.check
import kotlinx.cinterop.ExperimentalForeignApi
import platform.acl.aclFinalize
import platform.acl.aclInit

fun wStride(w: Int) = (w + 15) / 16 * 16
fun hStride(h: Int) = (h + 1) / 2 * 2

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    aclInit(null).check("aclInit")
    common.main(args)
    aclFinalize().check("aclFinalize")
}
