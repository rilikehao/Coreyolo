package common

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import platform.ffmpeg.AVFormatContext
import platform.ffmpeg.AVPacket

@OptIn(ExperimentalForeignApi::class)
interface Encoder : (Flow<Command.CommandImage>) -> Flow<CPointer<AVPacket>> {
    fun setFormatContext(formatContext: AVFormatContext)
}
