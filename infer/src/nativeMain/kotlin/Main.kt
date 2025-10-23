import cli.ArgParser
import kotlinx.cinterop.*
import platform.native.Main

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    val parser = ArgParser("YoloInfer")
    AppArguments.instance = AppArguments(parser)
    parser.parse(args)
    when (AppArguments.instance.sourceType) {
        AppArguments.SourceType.IMAGE -> staticCFunction { -> SourceImage.run() }
        AppArguments.SourceType.VIDEO -> staticCFunction { -> SourceVideo.run() }
    }.let { memScoped { Main(1, arrayOf("YoloInfer").toCStringArray(this), it) } }
}
