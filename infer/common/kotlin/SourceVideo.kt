package common

import DecodeH264
import EncodeH264
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
                        val decoded = when (AppConfig.instance.source.type) {
                            AppConfig.SourceType.VIDEO -> DecodeH264(InputRtsp(config.source))
                            AppConfig.SourceType.CAMERA -> Camera.open(config.source)()
                            else -> throw Error("不支持的视频来源")
                        }
                        val inferred = Inference(config.id, decoded)
                        val original = OutputRtsp.Context("rtsp://127.0.0.1:50554/original/${config.id}")
                        val processed = OutputRtsp.Context("rtsp://127.0.0.1:50554/processed/${config.id}")
                        val encoded = EncodeH264(config.id, original, processed, inferred)
                        OutputRtsp(encoded)
                        delay(5000)
                    }
                }
            }.joinAll()
        }
    }
}
