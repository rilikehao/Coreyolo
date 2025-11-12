import common.Utils.check
import kotlinx.cinterop.ExperimentalForeignApi
import platform.acl.aclFinalize
import platform.acl.aclInit

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    aclInit(null).check("aclInit")
    common.main(args)
    aclFinalize().check("aclFinalize")
}
