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
    const val VENV_PATH_HUAWEI_6 = "train/env-huawei-6"
    const val VENV_PATH_HUAWEI_8 = "train/env-huawei-8"

    private fun createVenv() {
        println("创建虚拟环境...")
        if (!File(VENV_PATH).exists()) {
            ProcessBuilder("uv", "venv", File(VENV_PATH).absolutePath, "--python", "3.12").runCommand()
        }
        if (!File(VENV_PATH_HUAWEI_6).exists()) {
            ProcessBuilder("uv", "venv", File(VENV_PATH_HUAWEI_6).absolutePath, "--python", "3.9").runCommand()
        }
        if (!File(VENV_PATH_HUAWEI_8).exists()) {
            ProcessBuilder("uv", "venv", File(VENV_PATH_HUAWEI_8).absolutePath, "--python", "3.9").runCommand()
        }
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

        listOf("6.0.1", "8.0.0").forEach { version ->
            val venvPath: String
            val amctOnnxVersion: String
            when(version) {
                "6.0.1" -> {
                    venvPath = VENV_PATH_HUAWEI_6
                    amctOnnxVersion = "0.7.4"
                }
                "8.0.0" -> {
                    venvPath = VENV_PATH_HUAWEI_8
                    amctOnnxVersion = "0.19.3"
                }
                else -> throw Error("版本不对")
            }
            listOf(
                "execstack",
                "Ascend-cann-amct_${version}_linux-x86_64.tar.gz",
                "Ascend-cann-toolkit_${version}_linux-x86_64.run",
            ).forEach {
                if (!File("train/deps/$it").exists()) {
                    ProcessBuilder(
                        "curl", "-o", File("train/deps/$it").absolutePath,
                        "https://f000.kw92.cyou/file/kunweiz92-YoloInfer/$it",
                    ).runCommand()
                }
            }

            File("train/deps/$version").mkdirs()

            println("安装华为虚拟环境相关依赖...")
            ProcessBuilder(
                "tar", "-xf", "Ascend-cann-amct_${version}_linux-x86_64.tar.gz", "-C", version,
            ).directory(File("train/deps")).runCommand()

            ProcessBuilder(
                "uv", "pip", "install",
                "onnx", "onnxruntime==1.8.0", "setuptools", "numpy<2", "opencv-python",
                "pip", "decorator", "sympy", "scipy", "attrs", "psutil",
                "../deps/$version/amct/amct_onnx/amct_onnx-$amctOnnxVersion-py3-none-linux_x86_64.whl",
                "--directory", File(venvPath).absolutePath,
            ).runCommand()

            ProcessBuilder(
                "chmod", "+x", "execstack",
            ).directory(File("train/deps")).runCommand()

            ProcessBuilder(
                "./execstack", "-c",
                File("$venvPath/lib/python3.9/site-packages/onnxruntime/capi/onnxruntime_pybind11_state.cpython-39-x86_64-linux-gnu.so").absolutePath,
            ).directory(File("train/deps")).runCommand()

            ProcessBuilder(
                "tar", "-xf",
                "amct_onnx_op.tar.gz",
            ).directory(File("train/deps/$version/amct/amct_onnx")).runCommand()

            ProcessBuilder(
                "bin/python", "../deps/$version/amct/amct_onnx/amct_onnx_op/setup.py", "install",
            ).directory(File(venvPath)).apply {
                environment()["PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION"] = "python"
            }.runCommand()

            ProcessBuilder(
                "chmod", "+x", "Ascend-cann-toolkit_${version}_linux-x86_64.run",
            ).directory(File("train/deps")).runCommand()

            val run = "../deps/Ascend-cann-toolkit_${version}_linux-x86_64.run"
            val path = File("train/deps/${version}").absolutePath
            ProcessBuilder(
                "bash", "-c",
                "export PYTHONPATH=. && source bin/activate && $run --quiet --nox11 --install --install-path=$path",
            ).directory(File(venvPath)).runCommand()
            ProcessBuilder("chmod", "-R", "+w", path).runCommand()
            ProcessBuilder("rm", "-rf", "${System.getProperty("user.home")}/Ascend").runCommand()
        }
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

//        println("转换为 MNN 模型...")
//        ProcessBuilder(
//            "bash", "-c",
//            "export PYTHONPATH=. && source bin/activate && cd ../src && python to_mnn.py --quant"
//        ).directory(File(VENV_PATH)).runCommand()
//
//        println("重命名量化模型...")
//        ProcessBuilder("mv", "../best_quant.mnn", "../best.x86_64").directory(File(VENV_PATH)).runCommand()
//
//        println("转换为 RKNN (RK3588) 模型...")
//        ProcessBuilder(
//            "bash", "-c",
//            "export PYTHONPATH=. && source bin/activate && cd ../src && python to_rknn_rk3588.py"
//        ).directory(File(VENV_PATH)).runCommand()
//
//        println("转换为 RKNN (RK3576) 模型...")
//        ProcessBuilder(
//            "bash", "-c",
//            "export PYTHONPATH=. && source bin/activate && cd ../src && python to_rknn_rk3576.py"
//        ).directory(File(VENV_PATH)).runCommand()

        println("华为量化... (310)")
        ProcessBuilder(
            "bash", "-c",
            "export PYTHONPATH=. && source bin/activate && cd ../src && python to_ascend_310.py"
        ).apply {
            environment()["PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION"] = "python"
        }.directory(File(VENV_PATH_HUAWEI_6)).runCommand()

        println("重命名量化模型...")
        ProcessBuilder("mv", "../best.om", "../best.ascend310").directory(File(VENV_PATH)).runCommand()

        println("华为量化... (310P3)")
        ProcessBuilder(
            "bash", "-c",
            "export PYTHONPATH=. && source bin/activate && cd ../src && python to_ascend_310P3.py"
        ).apply {
            environment()["PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION"] = "python"
        }.directory(File(VENV_PATH_HUAWEI_8)).runCommand()

        println("重命名量化模型...")
        ProcessBuilder("mv", "../best.om", "../best.ascend310P3").directory(File(VENV_PATH)).runCommand()

        listOf(
            "best.onnx", "best_deploy_model.onnx", "best_fake_quant_model.onnx", "best.mnn",
            "best_quant.json", "best_quant.mnn.json", "quant_config.json", "fusion_result.json",
        ).forEach { file -> File("train/$file").delete() }

        println("模型导出完成！")
    }

    fun clean() {
        println("======================================")
        println("清理训练环境")
        println("======================================")

        listOf(
            "best.rk3588", "best.rk3576", "best.x86_64", "best.ascend310",
        ).forEach { file -> File("train/$file").delete() }

        File(VENV_PATH).deleteRecursively()
        File(VENV_PATH_HUAWEI_6).deleteRecursively()
        File(VENV_PATH_HUAWEI_8).deleteRecursively()

        println("清理完成")
    }
}
