package common

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.*
import platform.native.HttpGet

@OptIn(ExperimentalForeignApi::class)
object SourceVideo : suspend () -> Unit {
    override suspend fun invoke() {
        Inference.use {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            AppConfig.instance.streams.map { config ->
                // HttpGet("http://127.0.0.1:50080/index/api/addStreamProxy?secret=21344657&vhost=__defaultVhost__&app=original&stream=${config.id}&url=${config.source}")
                scope.launch {
                    while (true) {
                        RtspInput(config.source).use {
                            RtspOutput(config.target)(config.id, Inference(config.id, it.frames()))
                        }
                        delay(5000)
                    }
                }
            }.joinAll()
        }
    }
}
