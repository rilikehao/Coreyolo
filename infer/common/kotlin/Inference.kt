package common

import cnames.structs.InferTask
import co.touchlab.kermit.Logger
import common.StringFormat.toString
import kotlinx.cinterop.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.runBlocking
import platform.native.*
import kotlin.math.max
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
object Inference : (String, Flow<Frame>) -> Flow<Frame>, AutoCloseable {
    data class Task(val timestamp: Instant, val inferTask: CPointer<InferTask>, var drop: Boolean)

    val infer = memScoped {
        val config = alloc<InferConfig>()
        config.path_model_ = AppConfig.instance.paths.model.cstr.ptr
        config.path_description_ = AppConfig.instance.paths.description.cstr.ptr
        config.threads_ = AppConfig.instance.processing.npuThreads
        CreateInfer(config.ptr)!!
    }
    val detect0Manager = Manager(AppConfig.instance.processing.npuThreads)
    val detect1Manager = Manager(AppConfig.instance.processing.cpuThreads)

    init {
        val image = CreateImageRGB24(1, 1)
        DrawRect(image, cValue<Rect>(), 0, 0, 0, "强制初始化".cstr)
        DestroyImage(image)
    }

    override fun close() = runBlocking {
        detect1Manager.close()
        detect0Manager.close()
        DestroyInfer(infer)
    }

    override fun invoke(id: String, input: Flow<Frame>): Flow<Frame> {
        var inputFrames = 0L
        var outputFrames = 0L
        var frame0 = TimeSource.Monotonic.markNow()
        var timestamp0 = Clock.System.now()
        var taskLast: CPointer<InferTask>? = null
        val draw = DrawScript(AppConfig.instance.paths.drawScript)
        return input.map { frame ->
            val task = Task(frame.timestamp, CreateInferTask()!!, false)
            SetImage(task.inferTask, frame.original)
            if (inputFrames == 0L) timestamp0 = frame.timestamp
            if (maxFrames(frame.timestamp - timestamp0) < inputFrames) {
                task.drop = true
            } else {
                ++inputFrames
            }
            if (task.drop) {
                CompletableDeferred(task)
            } else {
                detect0Manager.use { id -> task.also { Detect0(infer, task.inferTask, id) } }
            }
        }.buffer(Channel.UNLIMITED).map { deferred -> deferred.await() }.map { task ->
            if (task.drop) {
                CompletableDeferred(task)
            } else {
                detect1Manager.use { task.also { Detect1(infer, it.inferTask) } }
            }
        }.buffer(Channel.UNLIMITED).map { deferred -> deferred.await() }.map { task ->
            val origin = CreateImageCopy(GetImage(task.inferTask))
            if (task.drop) {
                try {
                    if (taskLast == null) {
                        GetImage(task.inferTask)
                    } else {
                        SetImage(taskLast, GetImage(task.inferTask))
                        draw.execute(task.timestamp, taskLast!!)
                        GetImage(taskLast)
                    }
                } finally {
                    DestroyInferTask(task.inferTask)
                }
            } else {
                try {
                    Logger.i {
                        if (outputFrames == 0L) frame0 = TimeSource.Monotonic.markNow()
                        val fps = (1.seconds / frame0.elapsedNow() * (++outputFrames)).toString(2)
                        val delayed = max(0L, (frame0.elapsedNow() - (task.timestamp - timestamp0)).inWholeMilliseconds)
                        val detections = SizeDetections(task.inferTask)
                        "[$id] 推理 FPS: $fps, 额外延迟 / ms: $delayed, 检测数量: $detections."
                    }
                    draw.execute(task.timestamp, task.inferTask)
                    GetImage(task.inferTask)
                } finally {
                    taskLast?.let { DestroyInferTask(it) }
                    taskLast = task.inferTask
                }
            }.let { Frame(task.timestamp, origin!!, it!!) }
        }.onCompletion {
            draw.close()
            taskLast?.let { DestroyInferTask(it) }
        }.buffer(Channel.UNLIMITED)
    }

    fun maxFrames(duration: Duration) = (duration * AppConfig.instance.processing.fpsYolo).inWholeSeconds
}
