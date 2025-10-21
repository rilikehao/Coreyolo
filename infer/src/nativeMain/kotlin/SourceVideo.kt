import StringFormat.toString
import cnames.structs.Image
import cnames.structs.InferTask
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import platform.native.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class, ExperimentalTime::class)
object SourceVideo : Runnable {
    const val THREADS = 3

    data class Task(val pts: Duration, val inferTask: CPointer<InferTask>)

    override fun run() = runBlocking {
        val tasks = PriorityQueue<Task>(1) { it.pts }
        val tasksAgent = PriorityQueueAgent(tasks)
        withJob({ tasksAgent.run() }) {
            withJob({ runSend(tasksAgent) }) {
                if (AppArguments.instance.pathTarget.isEmpty()) {
                    RAIIOutput().use { output -> withJob({ runReceive(receive(tasksAgent), output) }) { Exec() } }
                } else {
                    RtspOutput(AppArguments.instance.pathTarget).runReceive(receive(tasksAgent))
                }
            }
        }
    }

    suspend fun runSend(tasksAgent: PriorityQueueAgent<Task>) =
        memScoped {
            val config = alloc<InferConfig>()
            config.path_model_ = AppArguments.instance.pathModel.cstr.ptr
            config.path_description_ = AppArguments.instance.pathDescription.cstr.ptr
            config.threads_ = THREADS
            RAIIInfer(config.ptr)
        }.use { infer ->
            Video.open(AppArguments.instance.pathSource).use { video ->
                val manager0 = Manager(THREADS)
                val manager1 = Manager(THREADS)
                video.frames().flatMapMerge { (pts, frame) ->
                    flow {
                        manager0.use { id ->
                            when (id) {
                                null -> DestroyImage(frame)
                                else -> {
                                    val task = CreateInferTask()
                                    SetImage(task, frame)
                                    Detect0(infer.value, task, id)
                                    emit(Task(pts, task!!))
                                }
                            }
                        }
                    }
                }.flatMapMerge { task ->
                    flow {
                        manager1.use { id ->
                            when (id) {
                                null -> {
                                    DestroyImage(GetImage(task.inferTask))
                                    DestroyInferTask(task.inferTask)
                                }

                                else -> {
                                    Detect1(infer.value, task.inferTask)
                                    emit(task)
                                }
                            }
                        }
                    }
                }.collect { tasksAgent.send(it) }
            }
        }

    fun receive(tasksAgent: PriorityQueueAgent<Task>) = flow {
        var frame0: TimeSource.Monotonic.ValueTimeMark? = null
        var pts0 = Duration.ZERO
        var frames = 0
        DrawScript(AppArguments.instance.pathDrawScript).use { draw ->
            var ptsLast: Duration? = null
            while (true) {
                val task = tasksAgent.receive()
                if (ptsLast != null && task.pts < ptsLast) {
                    DestroyImage(GetImage(task.inferTask))
                } else {
                    ptsLast = task.pts
                    draw.execute(task.inferTask)
                    val text = StringBuilder()
                    if (frame0 == null) {
                        frame0 = TimeSource.Monotonic.markNow()
                        pts0 = task.pts
                    } else {
                        val fps = 1.seconds / (frame0.elapsedNow() / ++frames)
                        text.append("每秒帧数: ${fps.toString(2)} ")
                        val delayed = frame0.elapsedNow() - (task.pts - pts0)
                        if (delayed.isPositive()) text.append("额外延迟: $delayed ")
                    }
                    val detections = SizeDetections(task.inferTask)
                    text.append("检测数量: $detections")
                    println(text)
                    emit(Pair(task.pts, GetImage(task.inferTask)!!))
                }
                DestroyInferTask(task.inferTask)
            }
        }
    }

    suspend fun runReceive(receive: Flow<Pair<Duration, CPointer<Image>>>, output: RAIIOutput) {
        var delayMs = 0
        var frame0: TimeSource.Monotonic.ValueTimeMark? = null
        var pts0 = Duration.ZERO
        val delays = ArrayDeque<Pair<TimeSource.Monotonic.ValueTimeMark, Duration>>()
        receive.collect { (pts, frame) ->
            if (frame0 == null) {
                frame0 = TimeSource.Monotonic.markNow()
                pts0 = pts
            }
            val delayed = frame0.elapsedNow() - (pts - pts0)
            while (delays.isNotEmpty() && delayed < delays.last().second) delays.removeLast()
            delays.addLast(Pair(TimeSource.Monotonic.markNow(), delayed))
            while (delays.isNotEmpty() && 1.seconds < delays.first().first.elapsedNow()) delays.removeFirst()
            if (delays.isNotEmpty()) delayMs = delayMs.coerceAtLeast(delays.first().second.inWholeMilliseconds.toInt())
            delay(delayMs.milliseconds - delayed)
            SendToOutput(output.value, frame)
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
