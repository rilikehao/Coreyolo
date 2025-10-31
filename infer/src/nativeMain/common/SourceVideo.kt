package common

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.*
import platform.native.HttpGetWaitStatus

@OptIn(ExperimentalForeignApi::class)
object SourceVideo : suspend () -> Unit {
    override suspend fun invoke() {
        Inference.use {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            AppConfig.instance.streams.map { config ->
                val base = "http://127.0.0.1:50080/index/api/addStreamProxy?secret=21344657"
                val params = "&vhost=__defaultVhost__&app=original&stream=${config.id}&url=${config.source}"
                val status = HttpGetWaitStatus("$base$params")
                if (status != 200) throw Error("拉取流失败")
                scope.launch {
                    delay(5000)
                    while (true) {
                        launch {
                            delay(15000)
                            val baseRecord = "http://127.0.0.1:50080/index/api/startRecord?secret=21344657"
                            val paramsRecord0 = "&type=1&vhost=__defaultVhost__&app=original&stream=${config.id}"
                            val statusRecord0 = HttpGetWaitStatus("$baseRecord$paramsRecord0")
                            if (statusRecord0 != 200) throw Error("录制原始流失败")
                            val paramsRecord1 = "&type=1&vhost=__defaultVhost__&app=processed&stream=${config.id}"
                            val statusRecord1 = HttpGetWaitStatus("$baseRecord$paramsRecord1")
                            if (statusRecord1 != 200) throw Error("录制分析流失败")
                        }
                        RtspInput("rtsp://127.0.0.1:50554/original/${config.id}").use {
                            RtspOutput("rtsp://127.0.0.1:50554/processed/${config.id}")(
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
