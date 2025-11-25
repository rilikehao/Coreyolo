import common.Utils.checkEq0
import platform.acl.aclFinalize
import platform.acl.aclInit

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
typealias SwsContext = platform.ffmpeg.SwsContext

typealias FromRGBImage = common.RGB2YUV
typealias ToRGBImage = common.YUV2RGB
typealias EncoderVideoH264Fast = Codec.EncoderVideoH264Fast

fun wStride(w: Int) = (w + 15) / 16 * 16
fun hStride(h: Int) = (h + 1) / 2 * 2

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    aclInit(null).checkEq0("aclInit")
    common.main(args)
    aclFinalize().checkEq0("aclFinalize")
}
