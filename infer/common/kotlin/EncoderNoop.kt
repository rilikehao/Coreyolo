package common

import common.Utils.check
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import platform.ffmpeg.*
import platform.native.ToTimeString
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class EncoderNoop(val inputRtsp: InputRtsp, suggestedName: CompletableDeferred<String>?) : Encoder {
    val codec = avcodec_find_encoder_by_name("libx264").check("avcodec_find_encoder_by_name")

    override fun startTimeRealtime() = inputRtsp.formatCtx.start_time_realtime

    override fun initStream(formatContext: AVFormatContext) =
        avformat_new_stream(formatContext.ptr, codec).check("avformat_new_stream").also {
            avcodec_parameters_copy(it.pointed.codecpar, inputRtsp.stream.codecpar)
        }

    override fun invoke() = flow<CPointer<AVPacket>?> {}

    init {
        val name = ToTimeString(startTimeRealtime() / 1000.0).useContents { data_.toKString() }
        suggestedName?.complete(name)
    }
}
