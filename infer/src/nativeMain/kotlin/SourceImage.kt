import kotlinx.cinterop.*
import kotlinx.coroutines.Runnable
import platform.native.*

@OptIn(ExperimentalForeignApi::class)
object SourceImage : Runnable {
    override fun run() = memScoped {
        val config = alloc<InferConfig>()
        config.path_model_ = AppArguments.instance.pathModel.cstr.ptr
        config.path_description_ = AppArguments.instance.pathDescription.cstr.ptr
        config.threads_ = 1
        val infer = CreateInfer(config.ptr)
        val image = CreateImagePath(AppArguments.instance.pathSource)
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
