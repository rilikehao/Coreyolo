package common

import common.Utils.check
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.coroutines.flow.flow
import platform.ffmpeg.*
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class EncoderNoop(val inputRtsp: InputRtsp) : Encoder {
    val codec = avcodec_find_encoder_by_name("libx264").check("avcodec_find_encoder_by_name")

    override fun initStream(formatContext: AVFormatContext) =
        avformat_new_stream(formatContext.ptr, codec).check("avformat_new_stream").also {
            avcodec_parameters_copy(it.pointed.codecpar, inputRtsp.stream.codecpar)
        }

    override fun invoke() = flow<CPointer<AVPacket>?> {}
}
