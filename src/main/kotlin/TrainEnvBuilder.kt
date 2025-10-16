import SystemUtils.runCommand
import java.io.File

object TrainEnvBuilder {
    fun buildTrainEnv(pytorchVersion: String = "cpu") {
        println("======================================")
        println("YOLO11 统一环境构建")
        println("======================================")

        checkUV()
        createVenv()
        installDependencies(pytorchVersion)
        showUsage(pytorchVersion)

        println()
        println("脚本执行完成！")
    }

    private fun checkUV() {
        val process = ProcessBuilder("which", "uv").start()
        if (process.waitFor() != 0) {
            throw RuntimeException("uv 未安装，请先安装 uv\n安装命令: pip install uv 或访问 https://github.com/astral-sh/uv 获取安装说明")
        }
        println("uv 已安装")
    }

    const val VENV_PATH = "train/env"

    private fun createVenv() {
        println("检查虚拟环境是否存在...")

        if (File(VENV_PATH).exists()) {
            println("虚拟环境已存在, 使用现有虚拟环境")
            return
        }

        println("创建虚拟环境...")
        ProcessBuilder("uv", "venv", File(VENV_PATH).absolutePath, "--python", "3.12").runCommand()
        println("虚拟环境创建完成")
    }

    private fun installDependencies(pytorchVersion: String) {
        println("激活虚拟环境并安装依赖...")

        val torchInstallCommand = if (pytorchVersion == "cuda") {
            println("安装 PyTorch 2.4 CUDA 版本...")
            listOf("uv", "pip", "install", "torch==2.4.0", "torchvision==0.19.0", "torchaudio==2.4.0", "--index-url", "https://download.pytorch.org/whl/cu121")
        } else {
            println("安装 PyTorch 2.4 CPU 版本...")
            listOf("uv", "pip", "install", "torch==2.4.0", "torchvision==0.19.0", "torchaudio==2.4.0", "--index-url", "https://download.pytorch.org/whl/cpu")
        }

        ProcessBuilder(torchInstallCommand).directory(File(VENV_PATH)).runCommand()

        println("安装相关依赖...")
        val depsCommand = listOf(
            "uv", "pip", "install", "opencv-python", "psutil", "matplotlib", "PyYAML",
            "tqdm", "requests", "pandas", "scipy", "numpy", "pillow",
            "onnx>=1.18.0,<1.19.0", "rknn-toolkit2"
        )
        ProcessBuilder(depsCommand).directory(File(VENV_PATH)).runCommand()

        println("所有依赖安装完成")
    }

    private fun showUsage(pytorchVersion: String) {
        println()
        println("YOLO11 统一环境构建完成！")
        println()
        println("环境特性：")
        println("  - 统一的虚拟环境，同时支持训练和量化")
        if (pytorchVersion == "cuda") {
            println("  - PyTorch CUDA 版本，支持 GPU 加速训练")
        } else {
            println("  - PyTorch CPU 版本，适用于量化环境")
        }
        println("  - 包含所有必要的依赖包")
        println()
        println("使用方法：")
        println("  ./gradlew run --args='export'")
        println()
        println("更多使用说明请参考相关文档")
    }

    fun exportModels() {
        println("======================================")
        println("YOLO11 模型导出")
        println("======================================")

        if (!File(VENV_PATH).exists()) {
            throw RuntimeException("训练环境不存在，请先运行: ./gradlew run --args='train'")
        }

        println("导出 ONNX 模型...")
        ProcessBuilder(
            "bash", "-c",
            "export PYTHONPATH=. && source bin/activate && cd ../ultralytics && python ultralytics/engine/exporter.py"
        ).directory(File(VENV_PATH)).runCommand()

        println("转换为 MNN 模型...")
        ProcessBuilder(
            "bash", "-c",
            "export PYTHONPATH=. && source bin/activate && cd ../src && python to_mnn.py --quant"
        ).directory(File(VENV_PATH)).runCommand()

        println("转换为 RKNN 模型...")
        ProcessBuilder(
            "bash", "-c",
            "export PYTHONPATH=. && source bin/activate && cd ../src && python to_rknn.py"
        ).directory(File(VENV_PATH)).runCommand()

        println("重命名量化模型...")
        ProcessBuilder("mv", "../best_quant.mnn", "../best.mnn").directory(File(VENV_PATH)).runCommand()

        println("模型导出完成！")
    }

    fun clean() {
        println("======================================")
        println("清理训练环境")
        println("======================================")

        val filesToDelete = listOf(
            "best.onnx", "best.rknn", "best.mnn",
            "best_quant.mnn.json", "quant_config.json",
        )

        filesToDelete.forEach { file -> File("train/$file").delete() }

        File(VENV_PATH).deleteRecursively()

        println("清理完成")
    }
}
