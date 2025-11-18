package common

import cnames.structs.Image
import cnames.structs.InferTask
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
sealed class Command(val timestamp: Instant) {
    class CommandImage(t: Instant, val data: CPointer<Image>) : Command(t)
    class CommandLabel(t: Instant, val data: CPointer<InferTask>) : Command(t)
}
