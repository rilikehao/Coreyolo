import cnames.structs.Image
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
interface Video : AutoCloseable {
    fun frames(): Flow<Pair<Instant, CPointer<Image>>>

    companion object {
        fun open(source: String) =
            if (source.startsWith("rtsp://") || source.startsWith("rtsps://")) {
                RtspInput(source)
            } else {
                Camera.open(source)
            }
    }
}
