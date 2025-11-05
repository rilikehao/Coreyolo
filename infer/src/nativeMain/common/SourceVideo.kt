package common

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.*

@OptIn(ExperimentalForeignApi::class)
object SourceVideo : suspend () -> Unit {
    override suspend fun invoke() {
        Inference.use {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            AppConfig.instance.streams.map { config ->
                scope.launch {
                    while (true) {
                        RtspInput(config.source).use {
                            RtspOutput(
                                "rtsp://127.0.0.1:50554/original/${config.id}",
                                "rtsp://127.0.0.1:50554/processed/${config.id}",
                                config.id,
                                Inference(config.id, it.frames())
                            )
                        }
                        delay(5000)
                    }
                }
            }.joinAll()
        }
    }
}
