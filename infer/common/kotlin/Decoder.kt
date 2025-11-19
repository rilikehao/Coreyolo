package common

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

@OptIn(ExperimentalForeignApi::class)
interface Decoder : suspend () -> Flow<Command.CommandImage> {
    companion object {
        fun maxFrames(duration: Duration) = (duration * AppConfig.instance.processing.fpsDecode).inWholeSeconds
    }
}
