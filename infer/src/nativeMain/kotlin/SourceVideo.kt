import StringFormat.toString
import cnames.structs.InferTask
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import platform.native.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class, ExperimentalTime::class)
object SourceVideo : Runnable {
    const val THREADS = 6

    data class Task(
        val timestamp: Duration,
        val inferTask: CPointer<InferTask>,
        val id: Long,
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
            config.threads_ = THREADS
            RAIIInfer(config.ptr)
        }
        val draw = DrawScript(AppArguments.instance.pathDrawScript)
        val manager0 = Manager(THREADS)
        val manager1 = Manager(THREADS)
        var frames = 0L
        var sendId = 0L
        var receiveId = 0L
        val receiveQueue = mutableMapOf<Long, Task>()
        var frame0: TimeSource.Monotonic.ValueTimeMark? = null
        var timestamp0 = Duration.ZERO
        var inferTaskLast: CPointer<InferTask>? = null
        return map { frame ->
            val task = CreateInferTask()!!
            SetImage(task, frame.image)
            if (sendId == 0L) timestamp0 = frame.timestamp
            if (frames <= maxFrames(frame.timestamp - timestamp0)) {
                ++frames
                Task(frame.timestamp, task, sendId++, false)
            } else {
                Task(frame.timestamp, task, sendId++, true)
            }
        }.flatMapMerge(THREADS + 1) { task ->
            flow {
                if (!task.drop) manager0.use { id ->
                    if (id == null) {
                        println("NPU 过载丢帧")
                        task.drop = true
                    } else {
                        Detect0(infer.value, task.inferTask, id)
                    }
                }
                emit(task)
            }
        }.flatMapMerge(THREADS + 1) { task ->
            flow {
                if (!task.drop) manager1.use { id ->
                    if (id == null) {
                        println("CPU 过载丢帧")
                        task.drop = true
                    } else {
                        Detect1(infer.value, task.inferTask)
                    }
                }
                emit(task)
            }
        }.flatMapConcat { newTask ->
            flow {
                receiveQueue[newTask.id] = newTask
                while (true) {
                    val task = receiveQueue[receiveId] ?: break
                    ++receiveId
                    if (task.drop) {
                        if (inferTaskLast != null) {
                            SetImage(inferTaskLast, GetImage(task.inferTask))
                            DestroyInferTask(task.inferTask)
                            draw.execute(inferTaskLast!!)
                            emit(Video.Frame(task.timestamp, GetImage(inferTaskLast)!!))
                        } else {
                            emit(Video.Frame(task.timestamp, GetImage(task.inferTask)!!))
                            DestroyInferTask(task.inferTask)
                        }
                    } else {
                        draw.execute(task.inferTask)
                        val text = StringBuilder()
                        if (frame0 == null) {
                            frame0 = TimeSource.Monotonic.markNow()
                        } else {
                            val fps = 1.seconds / (frame0!!.elapsedNow() / (++frames).toDouble())
                            text.append("每秒帧数: ${fps.toString(2)} ")
                            val delayed = frame0!!.elapsedNow() - (task.timestamp - timestamp0)
                            if (delayed.isPositive()) text.append("额外延迟: $delayed ")
                        }
                        val detections = SizeDetections(task.inferTask)
                        text.append("检测数量: $detections")
                        println(text)
                        emit(Video.Frame(task.timestamp, GetImage(task.inferTask)!!))
                        inferTaskLast?.let { DestroyInferTask(it) }
                        inferTaskLast = task.inferTask
                    }
                }
            }
        }.onCompletion {
            inferTaskLast?.let { DestroyInferTask(it) }
            draw.close()
            infer.close()
        }
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
