package common

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import platform.ffmpeg.AVFormatContext
import platform.ffmpeg.AVPacket
import platform.ffmpeg.AVStream

@OptIn(ExperimentalForeignApi::class)
interface Encoder : () -> Flow<CPointer<AVPacket>?> {
    fun initStream(formatContext: AVFormatContext): CPointer<AVStream>
}
