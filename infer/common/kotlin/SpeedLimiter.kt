package common

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class SpeedLimiter(val upstream: Flow<Command>, val speed: Double): () -> Flow<Command> {
    override fun invoke(): Flow<Command> {
        var startSystemTime = Clock.System.now()
        var startVideoTime: Instant? = null
        var timestampLast: Instant? = null
        var timeShift = Duration.ZERO
        return upstream.onEach { command ->
            if (startVideoTime == null) {
                startVideoTime = command.timestamp
                startSystemTime = Clock.System.now()
                timestampLast = command.timestamp
            } else {
                if (1.seconds < command.timestamp - timestampLast!!) {
                    timeShift += command.timestamp - timestampLast!! - 1.seconds
                }
                timestampLast = command.timestamp
                command.timestamp = startVideoTime + (command.timestamp - timeShift - startVideoTime) / speed
                val duration = Clock.System.now() - startSystemTime
                val idealTimestamp = startVideoTime + duration
                delay((command.timestamp - idealTimestamp).inWholeMilliseconds)
            }
        }
    }
}
