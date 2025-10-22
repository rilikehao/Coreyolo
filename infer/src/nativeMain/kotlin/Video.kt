import cnames.structs.Image
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
interface Video : AutoCloseable {
    data class Frame(val timestamp: Duration, val image: CPointer<Image>)
    fun frames(): Flow<Frame>

    companion object {
        fun open(source: String) =
            if (source.startsWith("rtsp://") || source.startsWith("rtsps://")) {
                RtspInput(source)
            } else {
                Camera.open(source)
            }
    }
}
