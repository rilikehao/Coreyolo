package common

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.coroutines.*
import platform.native.HttpGet

@OptIn(ExperimentalForeignApi::class)
object SourceVideo : suspend () -> Unit {
    override suspend fun invoke() {
        Inference.use {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            AppConfig.instance.streams.map { config ->
                HttpGet("http://127.0.0.1:50080/index/api/addStreamProxy?secret=21344657&vhost=__defaultVhost__&app=original&stream=${config.id}&url=${config.source}".cstr)
                delay(5000)
                scope.launch {
                    while (true) {
                        RtspInput("rtsp://127.0.0.1:50554/original/${config.id}").use {
                            RtspOutput("rtsp://127.0.0.1:50554/processed/${config.id}")(config.id, Inference(config.id, it.frames()))
                        }
                        delay(5000)
                    }
                }
            }.joinAll()
        }
    }
}
