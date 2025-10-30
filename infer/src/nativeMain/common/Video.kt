package common

import cnames.structs.Image
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
interface Video : AutoCloseable {
    data class Frame(val timestamp: Instant, val image: CPointer<Image>)
    fun frames(): Flow<Frame>
}
