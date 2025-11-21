package common

import common.Utils.cPointer
import common.Utils.check
import common.Utils.timeZone
import common.Utils.withOptions
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.datetime.*
import platform.ffmpeg.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class InputRtsp(val url: String) : Input, suspend () -> Flow<CPointer<AVPacket>?> {
    lateinit var stream: AVStream
    lateinit var formatCtx: AVFormatContext

    override fun getStream() = stream
    override fun getFormatCtx() = formatCtx

    override suspend fun invoke(): Flow<CPointer<AVPacket>?> {
        val stopInstant = calculateNextMidnight()
        val formatContext = cPointer { ptr ->
            withOptions("fflags" to "nobuffer", "rtsp_transport" to "tcp") {
                avformat_open_input(ptr, url, null, it).check("avformat_open_input")
            }
        }
        avformat_find_stream_info(formatContext, null).check("avformat_find_stream_info")
        val videoStreamIndex = av_find_best_stream(formatContext, AVMEDIA_TYPE_VIDEO, -1, -1, null, 0)
        val packet = av_packet_alloc()!!
        stream = formatContext.pointed.streams!![videoStreamIndex]!!.pointed
        formatCtx = formatContext.pointed
        return flow {
            while (av_read_frame(formatContext, packet) != AVERROR_EOF) {
                if (Clock.System.now() >= stopInstant) {
                    println("InputRtsp: Reached 00:00 GMT+8, stopping stream.")
                    av_packet_unref(packet)
                    break
                }
                if (packet.pointed.stream_index == videoStreamIndex) {
                    emit(packet as CPointer<AVPacket>?)
                } else {
                    av_packet_unref(packet)
                }
            }
        }.onCompletion {
            avformat_close_input(cValuesOf(formatContext))
            av_packet_free(cValuesOf(packet))
        }
    }

    fun calculateNextMidnight(): Instant {
        val now = Clock.System.now()
        val localNow = now.toLocalDateTime(timeZone)
        val tomorrowDate = localNow.date.plus(DatePeriod(days = 1))
        val nextMidnightLocal = LocalDateTime(tomorrowDate, LocalTime(0, 0, 0))
        return nextMidnightLocal.toInstant(timeZone)
    }
}
