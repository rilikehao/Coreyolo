import StringFormat.toString
import cnames.structs.InferTask
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import platform.native.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class, ExperimentalTime::class)
object SourceVideo : Runnable {
    data class Task(val pts: Instant, val inferTask: CPointer<InferTask>)
    
    override fun run() = memScoped {
        RAIIOutput().use { output ->
            runBlocking {
                val tasks = PriorityQueue<Task> { it.pts }
                val tasksAgent = PriorityQueueAgent(tasks)
                withJob({ tasksAgent.run() }) {
                    withJob({ runSend(tasksAgent) }) {
                        withJob({ runReceive(tasksAgent, output) }) { Exec() }
                    }
                }
            }
        }
    }

    suspend fun runSend(tasksAgent: PriorityQueueAgent<Task>) = memScoped {
        val config = alloc<InferConfig>()
        config.path_model_ = AppArguments.instance.pathModel.cstr.ptr
        config.path_description_ = AppArguments.instance.pathDescription.cstr.ptr
        config.threads_ = 3
        val manager0 = Manager(config.threads_)
        val manager1 = Manager(config.threads_)
        RAIIInfer(config.ptr).use { infer ->
            Video.open(AppArguments.instance.pathSource).use { video ->
                video.frames()
                    .buffer(0, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                    .flatMapMerge(config.threads_) { (pts, frame) ->
                        flow {
                            manager0.use { id ->
                                val task = CreateInferTask()
                                SetImage(task, frame)
                                Detect0(infer.value, task, id)
                                emit(Task(pts, task!!))
                            }
                        }
                    }.flatMapMerge(config.threads_) { task ->
                        flow {
                            manager1.use {
                                Detect1(infer.value, task.inferTask)
                                emit(task)
                            }
                        }
                    }.collect { tasksAgent.send(it) }
            }
        }
    }

    suspend fun runReceive(tasksAgent: PriorityQueueAgent<Task>, output: RAIIOutput) = memScoped {
        var frame0: TimeSource.Monotonic.ValueTimeMark? = null
        var frames = 0
        var delayMs = 0
        DrawScript(AppArguments.instance.pathDrawScript).use { draw ->
            while (true) {
                val task = tasksAgent.receive()
                draw.execute(task.inferTask)
                val text = StringBuilder()
                if (frame0 == null) {
                    frame0 = TimeSource.Monotonic.markNow()
                } else {
                    val fps = 1.seconds / (frame0.elapsedNow() / ++frames)
                    text.append("每秒帧数: ${fps.toString(2)} ")
                    text.append("延迟/毫秒: $delayMs ")
                }
                val detections = SizeDetections(task.inferTask)
                text.append("检测数量: $detections")
                val delayed = Clock.System.now() - task.pts
                if (delayed < delayMs.milliseconds) {
                    delay(delayMs.milliseconds - delayed)
                } else {
                    delayMs = delayed.inWholeMilliseconds.toInt()
                }
                SendToOutput(output.value, GetImage(task.inferTask), text.toString())
                DestroyInferTask(task.inferTask)
            }
        }
    }

    class RAIIInfer(config: CPointer<InferConfig>) : AutoCloseable {
        val value = CreateInfer(config)
        override fun close() = DestroyInfer(value)
    }

    class RAIIOutput() : AutoCloseable {
        val value = CreateOutput()
        override fun close() = DestroyOutput(value)
    }

    suspend fun <T> withJob(runnable: suspend () -> Unit, block: suspend () -> T) {
        val job = CoroutineScope(Dispatchers.IO).launch { runnable() }
        block()
        job.cancelAndJoin()
    }
}
