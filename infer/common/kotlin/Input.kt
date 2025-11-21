package common

import kotlinx.cinterop.ExperimentalForeignApi
import platform.ffmpeg.AVFormatContext
import platform.ffmpeg.AVStream

@OptIn(ExperimentalForeignApi::class)
interface Input {
    fun getStream(): AVStream
    fun getFormatCtx(): AVFormatContext
}
