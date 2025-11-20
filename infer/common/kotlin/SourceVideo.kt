package common

import Codec
import cnames.structs.TcpSocket
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
        val outputs = mutableMapOf<String, Output>()
        val data = StableRef.create { stream: CPointer<ByteVar>, socket: CPointer<TcpSocket> ->
            CoroutineScope(mainContext).launch {
                when (val output = outputs[stream.toKStringFromUtf8()]) {
                    null -> SendData(socket, null, 0)
                    else -> {
                        var context: Output.Context? = null
                        context = output.addFmp4Stream { buf, size ->
                            if (context!!.stream == null) return@addFmp4Stream
                            if (!SendData(socket, buf?.reinterpret(), size)) {
                                context!!.stream = null
                                output.remove(context!!)
                            }
                        }
                    }
                }
            }
        }
        val serverThread = HttpServer(60000, cValue {
            func_ = staticCFunction { stream, socket, rawData ->
                rawData!!.asStableRef<(CPointer<ByteVar>, CPointer<TcpSocket>) -> Unit>().get()(stream!!, socket!!)
            }
            opaque_ = data.asCPointer()
        })
        Inference.use {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
                        } catch (e: Throwable) { e.printStackTrace() }
                        delay(2000)
                    }
                }
            }.joinAll()
        }
        StopHttpServer(serverThread)
        data.dispose()
    }
}
