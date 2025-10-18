import cnames.structs.AVDictionary
import cnames.structs.Image
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.flow.Flow
import platform.ffmpeg.av_dict_set
import kotlin.time.Duration

@OptIn(ExperimentalForeignApi::class)
class RtspOutput(val url: String) {
    suspend fun runReceive(receive: Flow<Pair<Duration, CPointer<Image>>>) = memScoped {
        val options = alloc<CPointerVar<AVDictionary>>()
        av_dict_set(options.ptr, "fflags", "nobuffer", 0)
        av_dict_set(options.ptr, "rtsp_transport", "tcp", 0)
        // open url for writing
        try {
            receive.collect { (pts, frame) ->
                // SendToOutput(pts, frame)
            }
        } finally {
            // cleanup
        }
    }
}
