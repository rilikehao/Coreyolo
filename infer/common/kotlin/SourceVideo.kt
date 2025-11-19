package common

import Codec
import cnames.structs.TcpSocket
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.native.HttpServer
import platform.native.SendData

@OptIn(ExperimentalForeignApi::class)
object SourceVideo : AutoCloseable, suspend () -> Unit {
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    val mainContext = newSingleThreadContext("OutputRtsp")

    override fun close() = mainContext.close()

    override suspend fun invoke() {
        val outputs = mutableMapOf<String, Output>()
        val data = StableRef.create { stream: CPointer<ByteVar>, socket: CPointer<TcpSocket> ->
            CoroutineScope(mainContext).launch {
                when (val output = outputs[stream.toKStringFromUtf8()]) {
                    null -> SendData(socket, null, 0)
                    else -> {
                        var context: Output.Context? = null
                        context = output.addFmp4Stream { buf, size ->
                            if (!SendData(socket, buf?.reinterpret(), size)) {
                                output.remove(context!!)
                            }
                        }
                    }
                }
            }
        }
        HttpServer(60000, cValue {
            func_ = staticCFunction { stream, socket, rawData ->
                rawData!!.asStableRef<(CPointer<ByteVar>, CPointer<TcpSocket>) -> Unit>().get()(stream!!, socket!!)
            }
            opaque_ = data.asCPointer()
        })
        Inference.use {
            AppConfig.instance.streams.mapIndexed { id, config ->
                val scope = CoroutineScope(Dispatchers.IO)
                scope.launch {
                    while (true) {
                        val decoded = when (AppConfig.instance.source.type) {
                            AppConfig.SourceType.CAMERA -> InputCamera.open(config.source)()
                            AppConfig.SourceType.VIDEO -> {
                                val inputRtsp = InputRtsp(config.source)
                                Codec.DecoderVideo(id, inputRtsp, inputRtsp())()
                            }
                            AppConfig.SourceType.VIDEO_KEEP -> {
                                val inputRtsp = InputRtsp(config.source)
                                val (main, side) = ForkPacket(inputRtsp())()
                                Codec.DecoderVideo(id, inputRtsp, main)()
                            }
                            else -> throw Error("不支持的视频来源")
                        }
                        when (config.storage) {
                            AppConfig.StorageType.DUMPED -> {
                                val (main, side) = ForkImage()(decoded)
                                val inferred = Inference(config.id)(main)
                                val drawn = Draw()(inferred)
                                scope.launch {
                                    val dump = Codec.EncoderVideoH265(config.id + "-dump")
                                    Output(dump).apply {
                                        addRtsp("rtsp://127.0.0.1:50554/dumped/${config.id}")
                                        invoke(side)
                                        close()
                                    }
                                }.also {
                                    val draw = Codec.EncoderVideoH264(config.id + "-draw")
                                    val output = Output(draw)
                                    CoroutineScope(mainContext).launch { outputs[config.id] = output }
                                    output(drawn)
                                    output.close()
                                }.join()
                            }

                            AppConfig.StorageType.ORIGINAL -> {
                                val inferred = Inference(config.id)(decoded)
                                val drawn = Draw()(inferred)
                                val draw = Codec.EncoderVideoH264(config.id + "-draw")
                                Output(draw).apply {
                                    addRtsp("rtsp://127.0.0.1:50554/drawn/${config.id}")
                                    invoke(drawn)
                                    close()
                                }
                            }
                        }
                        delay(2000)
                    }
                }
            }.joinAll()
        }
        data.dispose()
    }
}
