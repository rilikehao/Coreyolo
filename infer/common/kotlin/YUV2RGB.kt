package common

import SwsContext
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValuesOf
import platform.ffmpeg.*
import platform.native.Bits
import platform.native.BytesPerLine
import platform.native.CreateImageRGB24

@OptIn(ExperimentalForeignApi::class)
class YUV2RGB : AutoCloseable {
    var swsCtx: CPointer<SwsContext>? = null

    override fun close() = sws_freeContext(swsCtx)

    operator fun invoke(frame: AVFrame) =
        CreateImageRGB24(frame.width, frame.height)!!.also { image ->
            if (swsCtx == null) {
                swsCtx = sws_getContext(
                    frame.width, frame.height, frame.format,
                    frame.width, frame.height, AV_PIX_FMT_RGB24,
                    SWS_BILINEAR.toInt(), null, null, null,
                )
            }
            sws_scale(
                swsCtx, frame.data, frame.linesize, 0, frame.height,
                cValuesOf(Bits(image)), cValuesOf(BytesPerLine(image)),
            )
        }
}
