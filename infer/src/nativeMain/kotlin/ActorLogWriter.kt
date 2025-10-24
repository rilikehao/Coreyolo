import co.touchlab.kermit.CommonWriter
import co.touchlab.kermit.Severity
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel


object ActorLogWriter : CommonWriter(), AutoCloseable {
    data class LogData(
        val severity: Severity,
        val message: String,
        val tag: String,
        val throwable: Throwable?
    )

    val logChannel = Channel<LogData>(Channel.UNLIMITED)

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    val logDispatcher = newSingleThreadContext("KermitLogThread")

    init {
        CoroutineScope(logDispatcher).launch {
            for (data in logChannel) {
                super.log(data.severity, data.message, data.tag, data.throwable)
            }
        }
    }

    override fun log(
        severity: Severity,
        message: String,
        tag: String,
        throwable: Throwable?
    ) { if (isLoggable(tag, severity)) { logChannel.trySend(LogData(severity, message, tag, throwable)) } }

    override fun isLoggable(tag: String, severity: Severity) = !AppArguments.instance.mute

    override fun close() {
        logChannel.close()
        logDispatcher.close()
    }
}
