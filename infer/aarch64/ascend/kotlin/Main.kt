@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
typealias SwsContext = platform.ffmpeg.SwsContext

typealias FromRGBImage = common.RGB2YUV
typealias ToRGBImage = common.YUV2RGB
typealias EncoderVideoH264Vod = Codec.EncoderVideoH264Vod

fun main(args: Array<String>) = common.main(args)
