package common

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import platform.ffmpeg.AVFormatContext
import platform.ffmpeg.AVPacket
import platform.ffmpeg.AVStream
import kotlin.time.Duration

@OptIn(ExperimentalForeignApi::class)
interface Decoder : suspend (Flow<CPointer<AVPacket>?>) -> Flow<Command.CommandImage> {
    companion object {
        fun maxFrames(duration: Duration) = (duration * AppConfig.instance.processing.fpsDecode).inWholeSeconds
    }

    fun setStream(stream: AVStream)
    fun setFormatContext(formatContext: AVFormatContext)
}
