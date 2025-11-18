@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
typealias SwsContext = platform.ffmpeg.SwsContext
typealias FromRGBImage = common.RGB2YUV
typealias ToRGBImage = common.YUV2RGB

fun main(args: Array<String>) = common.main(args)
