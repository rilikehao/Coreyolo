import cli.ArgParser
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCStringArray
import platform.native.Main

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    ActorLogWriter.use { logWriter ->
        Logger.setLogWriters(logWriter)
        val parser = ArgParser("YoloInfer")
        AppArguments.instance = AppArguments(parser)
        parser.parse(args)
        when (AppArguments.instance.sourceType) {
            AppArguments.SourceType.IMAGE -> staticCFunction { -> SourceImage.run() }
            AppArguments.SourceType.VIDEO -> staticCFunction { -> SourceVideo.run() }
        }.let { memScoped { Main(1, arrayOf("YoloInfer").toCStringArray(this), it) } }
    }
}
