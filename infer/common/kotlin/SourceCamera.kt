package common

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.*

@OptIn(ExperimentalForeignApi::class)
object SourceCamera : suspend () -> Unit {
    override suspend fun invoke() {
        Inference.use {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            AppConfig.instance.streams.map { config ->
                scope.launch {
                    while (true) {
                        val decoded = Camera.open(config.source)()
                        val inferenced = Inference(config.id, decoded)
                        OutputRtsp(
                            "rtsp://127.0.0.1:50554/original/${config.id}",
                            "rtsp://127.0.0.1:50554/processed/${config.id}",
                            config.id, inferenced,
                        )
                        delay(5000)
                    }
                }
            }.joinAll()
        }
    }
}
