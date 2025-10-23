import cli.ArgParser
import kotlinx.cinterop.*
import platform.ffmpeg.AV_ERROR_MAX_STRING_SIZE
import platform.ffmpeg.av_make_error_string
import platform.native.Main

@OptIn(ExperimentalForeignApi::class)
fun Int.check(api: String) {
    if (this < 0) {
        memScoped {
            val buf = allocArray<ByteVar>(AV_ERROR_MAX_STRING_SIZE)
            av_make_error_string(buf.pointed.ptr, AV_ERROR_MAX_STRING_SIZE.toULong(), this@check)
            throw Error("$api 失败: ${buf.toKString()}")
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
fun <T : CPointed> CPointer<T>?.check(api: String): CPointer<T> = this ?: throw Error("$api 失败")

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
