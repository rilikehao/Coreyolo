import kotlinx.cinterop.*
import kotlinx.coroutines.Runnable
import platform.native.*

@OptIn(ExperimentalForeignApi::class)
object SourceImage : Runnable {
    override fun run() = memScoped {
        val config = alloc<InferConfig>()
        config.path_model_ = AppConfig.instance.paths.model.cstr.ptr
        config.path_description_ = AppConfig.instance.paths.description.cstr.ptr
        config.threads_ = 1
        CreateInfer(config.ptr)
    }.let { infer ->
        val image = CreateImagePath(AppConfig.instance.paths.source)
        val task = CreateInferTask()
        SetImage(task, image)
        Detect0(infer, task, 0)
        Detect1(infer, task)
        for (i in 0..<SizeDetections(task)) {
            val detect = PtrDetections(task)!![i]
            println("Detect $i:")
            println("${detect.name_!!.toKString()} ${detect.score_}")
            println("(${detect.bound_.x0_}-${detect.bound_.x1_}, ${detect.bound_.y0_}-${detect.bound_.y1_})")
        }
        DestroyInferTask(task)
        DestroyImage(image)
        DestroyInfer(infer)
    }
}
