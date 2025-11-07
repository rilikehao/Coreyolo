package common

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCStringArray
import kotlinx.coroutines.runBlocking
import platform.native.Main
import platform.posix.exit

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: YoloInfer <config-file.toml>")
        return exit(1)
    }
    
    ActorLogWriter.use { logWriter ->
        Logger.setLogWriters(logWriter)
        AppConfig.loadFromFile(args[0])
        
        when (AppConfig.instance.source.type) {
            AppConfig.SourceType.IMAGE -> staticCFunction { -> SourceImage() }
            AppConfig.SourceType.VIDEO -> staticCFunction { -> runBlocking { SourceVideo() } }
            AppConfig.SourceType.CAMERA -> staticCFunction { -> runBlocking { SourceCamera() } }
        }.let { memScoped { Main(1, arrayOf("YoloInfer").toCStringArray(this), it) } }
    }
}
