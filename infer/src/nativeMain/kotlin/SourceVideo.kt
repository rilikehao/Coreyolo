import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.*

@OptIn(ExperimentalForeignApi::class)
object SourceVideo : suspend () -> Unit {
    override suspend fun invoke() {
        Inference.use {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            AppConfig.instance.streams.map { config ->
                scope.launch {
                    Video.open(config.source).use {
                        RtspOutput(config.target)(config.id, Inference(config.id, it.frames()))
                    }
                }
            }.joinAll()
        }
    }
}
