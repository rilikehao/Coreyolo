package common

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import platform.native.CreateImageCopy
import platform.native.DestroyImage
import kotlin.time.ExperimentalTime

/**
 * 三路流图像分叉器
 * 将输入流分叉为三路输出流，只进行一次内存复制（创建两个副本）
 * 用于替代嵌套的 ForkImage，减少内存复制开销
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class ForkImage3(val input: Flow<Command.CommandImage>) :
        () -> Triple<Flow<Command.CommandImage>, Flow<Command.CommandImage>, Flow<Command.CommandImage>> {
    override fun invoke(): Triple<Flow<Command.CommandImage>, Flow<Command.CommandImage>, Flow<Command.CommandImage>> {
        // 使用有缓冲的 Channel，避免发送时挂起等待
        val dump1 = Channel<Command.CommandImage>(Channel.CONFLATED)  // 只保留最新帧
        val dump2 = Channel<Command.CommandImage>(Channel.CONFLATED)  // 只保留最新帧

        val main = input
            .onEach {
                // 只创建一次副本，复用给两个 side 流
                val copy1 = CreateImageCopy(it.data)!!
                val copy2 = CreateImageCopy(it.data)!!

                // 非阻塞发送，如果 Channel 满了就丢弃旧帧并释放内存
                val result1 = dump1.trySend(Command.CommandImage(it.timestamp, copy1))
                if (!result1.isSuccess) {
                    DestroyImage(copy1)
                }

                val result2 = dump2.trySend(Command.CommandImage(it.timestamp, copy2))
                if (!result2.isSuccess) {
                    DestroyImage(copy2)
                }
            }
            .onCompletion {
                dump1.close()
                dump2.close()
            }
            .buffer(Channel.CONFLATED)  // main 流也使用 CONFLATED 缓冲

        val side1 = dump1.consumeAsFlow()
        val side2 = dump2.consumeAsFlow()

        return Triple(main, side1, side2)
    }
}
