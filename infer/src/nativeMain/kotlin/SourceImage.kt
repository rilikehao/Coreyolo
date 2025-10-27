import kotlinx.cinterop.*
import platform.native.*

@OptIn(ExperimentalForeignApi::class)
object SourceImage : () -> Unit {
    override fun invoke() = memScoped {
        val config = alloc<InferConfig>()
        config.path_model_ = AppConfig.instance.paths.model.cstr.ptr
        config.path_description_ = AppConfig.instance.paths.description.cstr.ptr
        config.threads_ = 1
        CreateInfer(config.ptr)
    }.let { infer ->
        AppConfig.instance.streams.forEach { streamConfig ->
            val image = CreateImagePath(streamConfig.source)
            val task = CreateInferTask()
            SetImage(task, image)
            Detect0(infer, task, 0)
            Detect1(infer, task)
            repeat (SizeDetections(task)) { i ->
                val detect = PtrDetections(task)!![i]
                println("Detect $i:")
                println("${detect.name_!!.toKString()} ${detect.score_}")
                println("(${detect.bound_.x0_}-${detect.bound_.x1_}, ${detect.bound_.y0_}-${detect.bound_.y1_})")
            }
            DestroyInferTask(task)
            DestroyImage(image)
        }
        DestroyInfer(infer)
    }
}
