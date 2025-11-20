package common

import Codec
import cnames.structs.TcpSocket
import co.touchlab.kermit.Logger
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.native.HttpServer
import platform.native.SendData
import platform.native.StopHttpServer

@OptIn(ExperimentalForeignApi::class)
object SourceVideo : AutoCloseable, suspend () -> Unit {
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    val mainContext = newSingleThreadContext("OutputRtsp")

    override fun close() = mainContext.close()

    override suspend fun invoke() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val outputs = mutableMapOf<String, Output>()
        val data = StableRef.create { stream: CPointer<ByteVar>,
                                      begin: Double,
                                      end: Double,
                                      socket: CPointer<TcpSocket> ->
            val id = stream.toKStringFromUtf8()
            if (begin.isInfinite()) {
                CoroutineScope(mainContext).launch {
                    when (val output = outputs[id]) {
                        null -> SendData(socket, null, 0)
                        else -> {
                            var context: Output.Context? = null
                            context = output.addFmp4Stream { buf, size ->
                                if (context!!.stream != null) {
                                    if (!SendData(socket, buf?.reinterpret(), size)) {
                                        context!!.stream = null
                                        output.remove(context!!)
                                    }
                                }; size
                            }
                        }
                    }
                }
            } else {
                var vod: Job? = null
                vod = scope.launch {
                    val inputFmp4 = InputRtsp(AppConfig.instance.streams[0].source)
                    // val inputFmp4 = InputFmp4(stream, begin, end)
                    val decoded = Codec.DecoderVideo(1, inputFmp4, inputFmp4())()
                    val inferred = Inference("$id-vod", decoded)()
                    val drawn = Draw(inferred)()
                    val encoder = Codec.EncoderVideoH264("$id-vod", drawn)
                    Output(encoder, encoder()).use {
                        var context: Output.Context? = null
                        context = it.addFmp4Stream { buf, size ->
                            if (context!!.stream != null) {
                                if (!SendData(socket, buf?.reinterpret(), size)) {
                                    context!!.stream = null
                                    it.remove(context!!).invokeOnCompletion { vod!!.cancel() }
                                }
                            }; size
                        }
                        it.invoke()
                    }
                }
            }
        }
        val serverThread = HttpServer(60000, cValue {
            func_ = staticCFunction { stream, begin, end, socket, rawData ->
                rawData!!.asStableRef<(CPointer<ByteVar>, Double, Double, CPointer<TcpSocket>) -> Unit>()
                    .get()(stream!!, begin, end, socket!!)
            }
            opaque_ = data.asCPointer()
        })
        Inference.use {
            AppConfig.instance.streams.mapIndexed { id, config ->
                scope.launch {
                    while (true) {
                        try {
                            coroutineScope {
                                when (AppConfig.instance.source.type) {
                                    AppConfig.SourceType.CAMERA -> {
                                        val decoded = InputCamera.open(config.source)()
                                        val (main, side) = ForkImage(decoded)()
                                        scope.launch {
                                            val encoder = Codec.EncoderVideoH265(config.id, side)
                                            Output(encoder, encoder()).use {
                                                it.addFmp4(config.id)
                                                it.invoke()
                                            }
                                        }.also {
                                            val inferred = Inference(config.id, main)()
                                            val drawn = Draw(inferred)()
                                            val encoder = Codec.EncoderVideoH264(config.id, drawn)
                                            Output(encoder, encoder()).use {
                                                CoroutineScope(mainContext).launch { outputs[config.id] = it }
                                                it.invoke()
                                            }
                                        }.join()
                                    }

                                    AppConfig.SourceType.VIDEO -> {
                                        val inputRtsp = InputRtsp(config.source)
                                        val decoded = Codec.DecoderVideo(id, inputRtsp, inputRtsp())()
                                        val (main, side) = ForkImage(decoded)()
                                        scope.launch {
                                            val encoder = Codec.EncoderVideoH265(config.id, side)
                                            Output(encoder, encoder()).use {
                                                it.addFmp4(config.id)
                                                it.invoke()
                                            }
                                        }.also {
                                            val inferred = Inference(config.id, main)()
                                            val drawn = Draw(inferred)()
                                            val encoder = Codec.EncoderVideoH264(config.id, drawn)
                                            Output(encoder, encoder()).use {
                                                CoroutineScope(mainContext).launch { outputs[config.id] = it }
                                                it.invoke()
                                            }
                                        }.join()
                                    }

                                    AppConfig.SourceType.VIDEO_KEEP -> {
                                        val inputRtsp = InputRtsp(config.source)
                                        val (main, side) = ForkPacket(inputRtsp())()
                                        scope.launch {
                                            Output(EncoderNoop(inputRtsp), side).use {
                                                it.addFmp4(config.id)
                                                it.invoke()
                                            }
                                        }.also {
                                            val decoded = Codec.DecoderVideo(id, inputRtsp, main)()
                                            val inferred = Inference(config.id, decoded)()
                                            val drawn = Draw(inferred)()
                                            val encoder = Codec.EncoderVideoH264(config.id, drawn)
                                            Output(encoder, encoder()).use {
                                                CoroutineScope(mainContext).launch { outputs[config.id] = it }
                                                it.invoke()
                                            }
                                        }.join()
                                    }

                                    else -> throw Error("不支持的视频来源")
                                }
                            }
                        } catch (e: Throwable) {
                            Logger.w { e.message.toString() }
                        }
                        delay(2000)
                    }
                }
            }.joinAll()
        }
        StopHttpServer(serverThread)
        data.dispose()
    }
}
