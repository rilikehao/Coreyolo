package common

import Codec
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.*
import platform.native.HttpGetWaitStatus

@OptIn(ExperimentalForeignApi::class)
object SourceVideo : suspend () -> Unit {
    override suspend fun invoke() {
        Inference.use {
            AppConfig.instance.streams.mapIndexed { id, config ->
                val scope = CoroutineScope(Dispatchers.IO)
                val base = "http://127.0.0.1:50080/index/api/addStreamProxy?secret=21344657"
                val params = "&vhost=__defaultVhost__&app=original&stream=${config.id}&url=${config.source}"
                val status = HttpGetWaitStatus("$base$params")
                if (status != 200) throw Error("拉取流失败")
                val input = "rtsp://127.0.0.1:50554/original/${config.id}"
                scope.launch {
                    while (true) {
                        delay(2000)
                        scope.launch {
                            delay(12000)
                            val base = "http://127.0.0.1:50080/index/api/startRecord?secret=21344657"
                            val params = "&type=0&vhost=__defaultVhost__&app=${config.storage}&stream=${config.id}"
                            val status = HttpGetWaitStatus("$base$params")
                            if (status != 200) throw Error("录制流失败")
                        }
                        val decoded = when (AppConfig.instance.source.type) {
                            AppConfig.SourceType.VIDEO -> InputRtsp(input, Codec.DecoderVideo(id))
                            AppConfig.SourceType.CAMERA -> InputCamera.open(config.source)
                            else -> throw Error("不支持的视频来源")
                        }()
                        val (main, side) = Fork()(decoded)
                        val inferred = Inference(config.id)(main)
                        val drawed = Draw()(inferred)
                        val jobDump = if (config.storage == "dumped") {
                            scope.launch {
                                val dump = Codec.EncoderVideoH265(config.id + "-dump")
                                OutputRtsp("rtsp://127.0.0.1:50554/dumped/${config.id}", dump)(side)
                            }
                        } else null
                        val draw = Codec.EncoderVideoH264(config.id + "-draw")
                        OutputRtsp("rtsp://127.0.0.1:50554/drawed/${config.id}", draw)(drawed)
                        jobDump?.cancelAndJoin()
                    }
                }
            }.joinAll()
        }
    }
}
