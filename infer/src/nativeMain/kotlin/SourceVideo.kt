import StringFormat.toString
import cnames.structs.InferTask
import co.touchlab.kermit.Logger
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import platform.native.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class, ExperimentalTime::class)
object SourceVideo : Runnable {
    data class Task(
        val timestamp: Duration,
        val inferTask: CPointer<InferTask>,
        var drop: Boolean,
    )

    override fun run() = runBlocking {
        val video = Video.open(AppArguments.instance.pathSource)
        val detected = video.frames().onCompletion { video.close() }.detect()
        if (AppArguments.instance.pathTarget.isEmpty()) {
            RAIIOutput().use { output -> withJob({ runReceive(detected, output) }) { Exec() } }
        } else {
            RtspOutput(AppArguments.instance.pathTarget).runReceive(detected)
        }
    }

    fun Flow<Video.Frame>.detect(): Flow<Video.Frame> {
        val infer = memScoped {
            val config = alloc<InferConfig>()
            config.path_model_ = AppArguments.instance.pathModel.cstr.ptr
            config.path_description_ = AppArguments.instance.pathDescription.cstr.ptr
            config.threads_ = Device.THREADS
            RAIIInfer(config.ptr)
        }
        val draw = DrawScript(AppArguments.instance.pathDrawScript)
        val manager0 = Manager(Device.THREADS) { task: Task ->
            Logger.w { "卷积过载丢帧" }
            task.also { it.drop = true }
        }
        val manager1 = Manager(Device.THREADS) { task: Task ->
            Logger.w { "后处理过载丢帧" }
            task.also { it.drop = true }
        }
        var inputFrames = 0L
        var outputFrames = 0L
        var frame0: TimeSource.Monotonic.ValueTimeMark? = null
        var timestamp0 = Duration.ZERO
        var taskLast: CPointer<InferTask>? = null
        return map { frame ->
            val task = Task(frame.timestamp, CreateInferTask()!!, false)
            SetImage(task.inferTask, frame.image)
            if (inputFrames == 0L) timestamp0 = frame.timestamp
            if (maxFrames(frame.timestamp - timestamp0) < inputFrames) {
                task.drop = true
            } else {
                ++inputFrames
            }
            if (task.drop) {
                CompletableDeferred(task)
            } else {
                manager0.use(task) { id -> task.also { Detect0(infer.value, it.inferTask, id) } }
            }
        }.buffer(Channel.UNLIMITED).map { deferred -> deferred.await() }.map { task ->
            if (task.drop) {
                CompletableDeferred(task)
            } else {
                manager1.use(task) { task.also { Detect1(infer.value, it.inferTask) } }
            }
        }.buffer(Channel.UNLIMITED).map { deferred -> deferred.await() }.map { task ->
            if (!task.drop) {
                try {
                    Logger.i {
                        val text = StringBuilder()
                        if (frame0 == null) {
                            frame0 = TimeSource.Monotonic.markNow()
                        } else {
                            val fps = 1.seconds / (frame0.elapsedNow() / (++outputFrames).toDouble())
                            text.append("编码前的每秒帧数: ${fps.toString(2)} ")
                            val delayed = frame0.elapsedNow() - (task.timestamp - timestamp0)
                            if (delayed.isPositive()) text.append("额外延迟: $delayed ")
                        }
                        val detections = SizeDetections(task.inferTask)
                        text.append("检测数量: $detections")
                        text.toString()
                    }
                    draw.execute(task.inferTask)
                    GetImage(task.inferTask)
                } finally {
                    taskLast?.let { DestroyInferTask(it) }
                    taskLast = task.inferTask
                }
            } else {
                try {
                    if (taskLast == null) {
                        GetImage(task.inferTask)
                    } else {
                        SetImage(taskLast, GetImage(task.inferTask))
                        draw.execute(taskLast)
                        GetImage(taskLast)
                    }
                } finally {
                    DestroyInferTask(task.inferTask)
                }
            }.let { Video.Frame(task.timestamp, it!!) }
        }.onCompletion {
            taskLast?.let { DestroyInferTask(it) }
            draw.close()
            infer.close()
        }.buffer(1)
    }

    suspend fun runReceive(receive: Flow<Video.Frame>, output: RAIIOutput) {
        var delayMs = 0
        var frame0: TimeSource.Monotonic.ValueTimeMark? = null
        var timestamp0 = Duration.ZERO
        val delays = ArrayDeque<Pair<TimeSource.Monotonic.ValueTimeMark, Duration>>()
        receive.collect { input ->
            if (frame0 == null) {
                frame0 = TimeSource.Monotonic.markNow()
                timestamp0 = input.timestamp
            }
            val delayed = frame0.elapsedNow() - (input.timestamp - timestamp0)
            while (delays.isNotEmpty() && delayed < delays.last().second) delays.removeLast()
            delays.addLast(Pair(TimeSource.Monotonic.markNow(), delayed))
            while (delays.isNotEmpty() && 1.seconds < delays.first().first.elapsedNow()) delays.removeFirst()
            if (delays.isNotEmpty()) delayMs = delayMs.coerceAtLeast(delays.first().second.inWholeMilliseconds.toInt())
            delay(delayMs.milliseconds - delayed)
            SendToOutput(output.value, input.image)  // image 由 SendToOutput 负责销毁
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

    fun maxFrames(duration: Duration) = (duration * AppArguments.instance.fps).inWholeSeconds
}
