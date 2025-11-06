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
    const val VENV_PATH_HUAWEI = "train/env-huawei"

    private fun createVenv() {
        println("检查虚拟环境是否存在...")

        if (File(VENV_PATH).exists() && File(VENV_PATH_HUAWEI).exists()) {
            println("虚拟环境已存在, 使用现有虚拟环境")
            return
        }

        println("创建虚拟环境...")
        ProcessBuilder("uv", "venv", File(VENV_PATH).absolutePath, "--python", "3.12").runCommand()
        ProcessBuilder("uv", "venv", File(VENV_PATH_HUAWEI).absolutePath, "--python", "3.11").runCommand()
        println("虚拟环境创建完成")
    }

    private fun installDependencies(pytorchVersion: String) {
        println("激活虚拟环境并安装依赖...")

        if (pytorchVersion == "cuda") {
            println("安装 PyTorch 2.4 CUDA 版本...")
            ProcessBuilder(
                "uv", "pip", "install",
                "torch==2.4.0", "torchvision==0.19.0", "torchaudio==2.4.0",
                "--index-url", "https://download.pytorch.org/whl/cu121",
                "--directory", File(VENV_PATH).absolutePath
            ).runCommand()
        } else {
            println("安装 PyTorch 2.4 CPU 版本...")
            ProcessBuilder(
                "uv", "pip", "install",
                "torch==2.4.0", "torchvision==0.19.0", "torchaudio==2.4.0",
                "--index-url", "https://download.pytorch.org/whl/cpu",
                "--directory", File(VENV_PATH).absolutePath,
            ).runCommand()
        }

        println("安装相关依赖...")
        ProcessBuilder(
            "uv", "pip", "install", "opencv-python", "psutil", "matplotlib", "PyYAML",
            "tqdm", "requests", "pandas", "scipy", "numpy", "pillow",
            "onnx>=1.18.0,<1.19.0", "rknn-toolkit2",
            "--directory", File(VENV_PATH).absolutePath,
        ).runCommand()

        println("下载华为依赖...")
        File("train/deps").mkdirs()

        listOf(
            "amct_onnx_op.tar.gz",
            "amct_onnx-0.23.2-py3-none-linux_x86_64.whl",
            "execstack",
            "Ascend-cann-toolkit_8.3.RC1.alpha003_linux-x86_64.run",
        ).forEach {
            if (!File("train/deps/$it").exists()) {
                ProcessBuilder(
                    "curl", "-o", File("train/deps/$it").absolutePath,
                    "https://f000.backblazeb2.com/file/kunweiz92-YoloInfer/$it",
                ).runCommand()
            }
        }

        println("安装华为虚拟环境相关依赖...")
        ProcessBuilder(
            "uv", "pip", "install",
            "onnx==1.16.0", "onnxruntime==1.16.0", "setuptools", "numpy<2", "opencv-python", "pip",
            "../deps/amct_onnx-0.23.2-py3-none-linux_x86_64.whl",
            "--directory", File(VENV_PATH_HUAWEI).absolutePath,
        ).runCommand()

        ProcessBuilder(
            "chmod", "+x", "execstack",
        ).directory(File("train/deps")).runCommand()

        ProcessBuilder(
            "./execstack", "-c",
            "../env-huawei/lib/python3.11/site-packages/onnxruntime/capi/onnxruntime_pybind11_state.cpython-311-x86_64-linux-gnu.so",
        ).directory(File("train/deps")).runCommand()

        ProcessBuilder(
            "tar", "-xf",
            "amct_onnx_op.tar.gz",
        ).directory(File("train/deps")).runCommand()

        ProcessBuilder(
            "bin/python", "../deps/amct_onnx_op/setup.py", "install",
        ).directory(File(VENV_PATH_HUAWEI)).runCommand()

        ProcessBuilder(
            "chmod", "+x", "Ascend-cann-toolkit_8.3.RC1.alpha003_linux-x86_64.run",
        ).directory(File("train/deps")).runCommand()

        val run = "../deps/Ascend-cann-toolkit_8.3.RC1.alpha003_linux-x86_64.run"
        val path = File("train/deps").absolutePath
        ProcessBuilder(
            "bash", "-c",
            "export PYTHONPATH=. && source bin/activate && $run --install --install-path=$path <<< Y",
        ).directory(File(VENV_PATH_HUAWEI)).runCommand()

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
        println("  ./gradlew run --args='train-export'")
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

        println("重命名量化模型...")
        ProcessBuilder("mv", "../best_quant.mnn", "../best.x86_64").directory(File(VENV_PATH)).runCommand()

        println("转换为 RKNN (RK3588) 模型...")
        ProcessBuilder(
            "bash", "-c",
            "export PYTHONPATH=. && source bin/activate && cd ../src && python to_rknn_rk3588.py"
        ).directory(File(VENV_PATH)).runCommand()

        println("转换为 RKNN (RK3576) 模型...")
        ProcessBuilder(
            "bash", "-c",
            "export PYTHONPATH=. && source bin/activate && cd ../src && python to_rknn_rk3576.py"
        ).directory(File(VENV_PATH)).runCommand()

        println("华为量化...")
        ProcessBuilder(
            "bash", "-c",
            "export PYTHONPATH=. && source bin/activate && cd ../src && python to_ascend.py"
        ).directory(File(VENV_PATH_HUAWEI)).runCommand()
        println("模型导出完成！")
    }

    fun clean() {
        println("======================================")
        println("清理训练环境")
        println("======================================")

        val filesToDelete = listOf(
            "best.onnx", "best.mnn",
            "best.rk3588", "best.rk3576", "best.x86_64",
            "best_quant.mnn.json", "quant_config.json",
        )

        filesToDelete.forEach { file -> File("train/$file").delete() }

        File(VENV_PATH).deleteRecursively()

        println("清理完成")
    }
}
