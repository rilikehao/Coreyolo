@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
typealias SwsContext = platform.ffmpeg.SwsContext
typealias FromRGBImage = common.RGB2YUV
typealias ToRGBImage = common.YUV2RGB
typealias EncoderVideoH264Fast = Codec.EncoderVideoH264

fun main(args: Array<String>) = common.main(args)
