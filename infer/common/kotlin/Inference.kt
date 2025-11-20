package common

import co.touchlab.kermit.Logger
import common.StringFormat.toString
import kotlinx.cinterop.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import platform.native.*
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class Inference(val id: String, val input: Flow<Command.CommandImage>) : () -> Flow<Command> {
    companion object : AutoCloseable {
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
    }

    override fun invoke(): Flow<Command> {
        var inputFrames = 0L
        var outputFrames = 0L
        var frame0 = TimeSource.Monotonic.markNow()
        var timestamp0 = Clock.System.now()
        return input.map { commandImage ->
            if (inputFrames == 0L) timestamp0 = commandImage.timestamp
            if (maxFrames(commandImage.timestamp - timestamp0) < inputFrames) {
                CompletableDeferred(commandImage)
            } else {
                ++inputFrames
                val task = CreateInferTask()!!
                SetImage(task, commandImage.data)
                detect0Manager.use { id ->
                    Command.CommandLabel(commandImage.timestamp, task).also { Detect0(infer, task, id) }
                }
            }
        }.buffer(Channel.UNLIMITED).map { deferred -> deferred.await() }.map { command ->
            when (command) {
                is Command.CommandImage -> CompletableDeferred(command)
                is Command.CommandLabel -> {
                    detect1Manager.use { command.also { Detect1(infer, command.data) } }
                }
            }
        }.buffer(Channel.UNLIMITED).map { deferred ->
            deferred.await().also { command ->
                if (command is Command.CommandLabel) {
                    Logger.i {
                        if (outputFrames == 0L) frame0 = TimeSource.Monotonic.markNow()
                        val fps = (1.seconds / frame0.elapsedNow() * (++outputFrames)).toString(2)
                        val delayed = frame0.elapsedNow() - (command.timestamp - timestamp0)
                        val delayMs = max(0L, delayed.inWholeMilliseconds)
                        val detections = SizeDetections(command.data)
                        "[$id] 推理 FPS: $fps, 额外延迟 / ms: $delayMs, 检测数量: $detections."
                    }
                }
            }
        }
    }

    fun maxFrames(duration: Duration) = (duration * AppConfig.instance.processing.fpsYolo).inWholeSeconds
}
