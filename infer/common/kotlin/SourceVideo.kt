package common

import DecodeVideo
import EncodeVideo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.*
import platform.native.HttpGetWaitStatus

@OptIn(ExperimentalForeignApi::class)
object SourceVideo : suspend () -> Unit {
    override suspend fun invoke() {
        Inference.use {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            AppConfig.instance.streams.mapIndexed { id, config ->
                val base = "http://127.0.0.1:50080/index/api/addStreamProxy?secret=21344657"
                val params = "&vhost=__defaultVhost__&app=original&stream=${config.id}&url=${config.source}"
                val status = HttpGetWaitStatus("$base$params")
                if (status != 200) throw Error("拉取流失败")
                val input = "rtsp://127.0.0.1:50554/original/${config.id}"
                scope.launch {
                    while (true) {
                        delay(5000)
                        scope.launch {
                            delay(5000)
                            val base = "http://127.0.0.1:50080/index/api/startRecord?secret=21344657"
                            val params = "&vhost=__defaultVhost__&app=dumped&stream=${config.id}"
                            val status = HttpGetWaitStatus("$base$params")
                            if (status != 200) throw Error("录制流失败")
                        }
                        val decoded = when (AppConfig.instance.source.type) {
                            AppConfig.SourceType.VIDEO -> DecodeVideo(id, InputRtsp(input))
                            AppConfig.SourceType.CAMERA -> Camera.open(config.source)()
                            else -> throw Error("不支持的视频来源")
                        }
                        val inferred = Inference(config.id, decoded)
                        val original = OutputRtsp.Context("rtsp://127.0.0.1:50554/dumped/${config.id}")
                        val processed = OutputRtsp.Context("rtsp://127.0.0.1:50554/processed/${config.id}")
                        val encoded = EncodeVideo(config.id, original, processed, inferred)
                        OutputRtsp(encoded)
                    }
                }
            }.joinAll()
        }
    }
}
