@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
typealias SwsContext = cnames.structs.SwsContext
typealias FromRGBImage = common.RGB2RGB
typealias EncoderVideoH264Vod = Codec.EncoderVideoH264

fun main(args: Array<String>) = common.main(args)
