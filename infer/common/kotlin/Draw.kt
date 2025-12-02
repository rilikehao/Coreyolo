package common

import cnames.structs.InferTask
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import platform.native.DestroyInferTask
import platform.native.GetImage
import platform.native.SetImage
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class Draw(val input: Flow<Command>, val enableHttp: Boolean) : suspend () -> Flow<Command.CommandImage> {
    override suspend fun invoke(): Flow<Command.CommandImage> {
        var taskLast: CPointer<InferTask>? = null
        val draw = DrawScript(AppConfig.instance.paths.drawScript, enableHttp)
        return input.map { command ->
            when (command) {
                is Command.CommandLabel -> {
                    try {
                        draw.execute(command.timestamp, command.data)
                        Command.CommandImage(command.timestamp, GetImage(command.data)!!)
                    } finally {
                        taskLast?.let { DestroyInferTask(it) }
                        taskLast = command.data
                    }
                }

                is Command.CommandImage -> {
                    if (taskLast == null) {
                        command
                    } else {
                        SetImage(taskLast, command.data)
                        draw.execute(command.timestamp, taskLast)
                        Command.CommandImage(command.timestamp, GetImage(taskLast)!!)
                    }
                }
            }
        }.onCompletion {
            println("draw die")
            draw.close()
            taskLast?.let { DestroyInferTask(it) }
            println("draw die!")
        }.buffer(Channel.UNLIMITED)
    }
}
