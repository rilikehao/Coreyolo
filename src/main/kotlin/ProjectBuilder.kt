import SystemUtils.runCommand
import java.io.File

object ProjectBuilder {
    fun buildRGA() {
        ToolchainManager.createMesonCrossFile(Config.RK3588)
        val rgaDir = File("rk3588/rkrga")
        val rgaBuildDir = File("rk3588/rkrga/build")
        cloneIfNeeded(rgaDir, "https://github.com/nyanmisaka/rk-mirrors.git")
        ProcessBuilder(
            "meson", "setup", rgaBuildDir.absolutePath,
            "--prefix=${File(Config.RK3588.installPrefix).absolutePath}",
            "--libdir=lib",
            "--buildtype=release",
            "--default-library=shared",
            "--cross-file=${File(Config.RK3588.toolchainTxt).absolutePath}",
            "-Dcpp_args=-fpermissive",
            "-Dlibrga_demo=false"
        ).directory(rgaDir).runCommand()
        ProcessBuilder("ninja", "-C", rgaBuildDir.absolutePath, "install").runCommand()
    }

    fun buildMPP() {
        ToolchainManager.createCmakeToolchainFile(Config.RK3588)
        val mppDir = File("rk3588/rkmpp")
        val mppBuildDir = File("rk3588/rkmpp/build")
        cloneIfNeeded(mppDir, "https://github.com/nyanmisaka/mpp.git")
        ProcessBuilder(
            "/usr/bin/cmake", mppDir.absolutePath,
            "-DCMAKE_TOOLCHAIN_FILE=${File(Config.RK3588.toolchainCmake).absolutePath}",
            "-DCMAKE_INSTALL_PREFIX=${File(Config.RK3588.installPrefix).absolutePath}",
            "-DCMAKE_BUILD_TYPE=Release",
            "-DBUILD_SHARED_LIBS=ON",
            "-DBUILD_TEST=OFF",
        ).directory(mppBuildDir).runCommand()
        ProcessBuilder("make", "-j${Runtime.getRuntime().availableProcessors()}").directory(mppBuildDir).runCommand()
        ProcessBuilder("make", "install").directory(mppBuildDir).runCommand()
    }

    fun buildRKNPU2() {
        val rknpu2Dir = File("rk3588/rknn-toolkit2")
        cloneIfNeeded(rknpu2Dir, "https://github.com/rockchip-linux/rknn-toolkit2.git")

        val targetLibDir = File("rk3588/root/usr/local/lib")
        val targetIncludeDir = File("rk3588/root/usr/local/include")
        targetLibDir.mkdirs()
        targetIncludeDir.mkdirs()

        val sourceLibDir = File("${rknpu2Dir.absolutePath}/rknpu2/runtime/Linux/librknn_api/aarch64")
        val sourceIncludeDir = File("${rknpu2Dir.absolutePath}/rknpu2/runtime/Linux/librknn_api/include")

        ProcessBuilder("cp", "-r", sourceLibDir.absolutePath + "/.", targetLibDir.absolutePath).runCommand()
        ProcessBuilder("cp", "-r", sourceIncludeDir.absolutePath + "/.", targetIncludeDir.absolutePath).runCommand()
    }

    fun buildMNN() = buildMNN(Config.X64)

    fun buildMNN(archConfig: Config.ArchConfig) {
        val mnnDir = File("${archConfig.name}/MNN")
        cloneIfNeeded(mnnDir, "https://github.com/alibaba/MNN.git")

        val mnnBuildDir = File("${archConfig.name}/MNN/build")
        mnnBuildDir.mkdirs()

        ProcessBuilder(
            "/usr/bin/cmake", mnnDir.absolutePath,
            "-DCMAKE_INSTALL_PREFIX=${File(archConfig.installPrefix).absolutePath}",
            "-DCMAKE_BUILD_TYPE=Release",
            "-DBUILD_SHARED_LIBS=ON",
            "-DMNN_BUILD_TOOLS=ON",
            "-DMNN_BUILD_QUANTOOLS=ON",
            "-DMNN_BUILD_CONVERTER=ON",
            "-DMNN_VULKAN=ON",
        ).directory(mnnBuildDir).runCommand()

        ProcessBuilder("make", "-j${Runtime.getRuntime().availableProcessors()}").directory(mnnBuildDir).runCommand()
        ProcessBuilder("make", "install").directory(mnnBuildDir).runCommand()

        val vulkanLib = File("${archConfig.name}/MNN/build/source/backend/vulkan/libMNN_Vulkan.so")
        val convertDepsLib = File("${archConfig.name}/MNN/build/tools/converter/libMNNConvertDeps.so")
        val trainLib = File("${archConfig.name}/MNN/build/tools/train/libMNNTrain.so")
        val trainUtilsLib = File("${archConfig.name}/MNN/build/tools/train/libMNNTrainUtils.so")
        val targetLibDir = File("${archConfig.installPrefix}/lib")

        if (vulkanLib.exists()) {
            ProcessBuilder("cp", vulkanLib.absolutePath, targetLibDir.absolutePath).runCommand()
        }

        if (convertDepsLib.exists()) {
            ProcessBuilder("cp", convertDepsLib.absolutePath, targetLibDir.absolutePath).runCommand()
        }

        if (trainLib.exists()) {
            ProcessBuilder("cp", trainLib.absolutePath, targetLibDir.absolutePath).runCommand()
        }

        if (trainUtilsLib.exists()) {
            ProcessBuilder("cp", trainUtilsLib.absolutePath, targetLibDir.absolutePath).runCommand()
        }

        val tools = listOf("MNNConvert", "quantized.out", "GetMNNInfo")
        val sourceBuildDir = File("${archConfig.name}/MNN/build")
        val targetBinDir = File("${archConfig.installPrefix}/bin")
        targetBinDir.mkdirs()

        tools.forEach { tool ->
            val toolFile = File(sourceBuildDir, tool)
            if (toolFile.exists()) {
                ProcessBuilder("cp", toolFile.absolutePath, targetBinDir.absolutePath).runCommand()
            }
        }
    }

    fun buildFFmpeg() = buildFFmpeg(Config.RK3588)

    fun buildFFmpeg(archConfig: Config.ArchConfig) {
        val ffmpegDir = File("${archConfig.name}/ffmpeg")
        cloneIfNeeded(ffmpegDir, "https://github.com/nyanmisaka/ffmpeg-rockchip.git")

        val configureArgs = when (archConfig.name) {
            "x64" -> arrayOf(
                "./configure",
                "--prefix=${File(archConfig.installPrefix).absolutePath}",
                "--arch=x86_64",
                "--target-os=linux",
                "--pkg-config=pkg-config",
                "--extra-cflags=${
                    arrayOf(
                        "${File(archConfig.installPrefix).absolutePath}/include",
                        "${File(archConfig.targetDir).absolutePath}/usr/include"
                    ).joinToString(" ") { "-I$it" }
                }",
                "--extra-ldflags=${
                    arrayOf(
                        "${File(archConfig.installPrefix).absolutePath}/lib",
                        "${File(archConfig.targetDir).absolutePath}/usr/lib"
                    ).joinToString(" ") { "-L$it" }
                }",
                "--enable-gpl",
                "--enable-version3",
                "--enable-libdrm",
                "--enable-shared",
                "--disable-static",
                "--disable-stripping",
                "--disable-doc",
            )

            else -> arrayOf(
                "./configure",
                "--prefix=${File(archConfig.installPrefix).absolutePath}",
                "--arch=arm64",
                "--target-os=linux",
                "--cross-prefix=${archConfig.targetArch}-",
                "--sysroot=${archConfig.sysrootDir}",
                "--pkg-config=pkg-config",
                "--extra-cflags=${
                    arrayOf(
                        "${File(archConfig.installPrefix).absolutePath}/include",
                        "${File(archConfig.targetDir).absolutePath}/usr/include"
                    ).joinToString(" ") { "-I$it" }
                }",
                "--extra-ldflags=${
                    arrayOf(
                        "${File(archConfig.installPrefix).absolutePath}/lib",
                        "${File(archConfig.targetDir).absolutePath}/usr/lib"
                    ).joinToString(" ") { "-L$it" }
                }",
                "--enable-gpl",
                "--enable-version3",
                "--enable-libdrm",
                "--enable-rkmpp",
                "--enable-rkrga",
                "--enable-shared",
                "--disable-static",
                "--disable-stripping",
                "--disable-doc",
            )
        }

        ProcessBuilder(*configureArgs).apply {
            environment()["PKG_CONFIG_LIBDIR"] = arrayOf(
                "${File(archConfig.targetDir).absolutePath}/usr/lib/pkgconfig",
                "${File(archConfig.installPrefix).absolutePath}/lib/pkgconfig",
            ).joinToString(":")
        }.directory(ffmpegDir).runCommand()
        ProcessBuilder("make", "-j${Runtime.getRuntime().availableProcessors()}").directory(ffmpegDir).runCommand()
        ProcessBuilder("make", "install").directory(ffmpegDir).runCommand()
    }

    fun buildNative() {
        Config.archConfigs.forEach { archConfig ->
            ToolchainManager.createCmakeToolchainFile(archConfig)
            val nativeDir = File("native")
            val buildDir = File("${archConfig.name}/native/build")
            buildDir.mkdirs()

            ProcessBuilder(
                "/usr/bin/cmake", nativeDir.absolutePath,
                "-DCMAKE_TOOLCHAIN_FILE=${File(archConfig.toolchainCmake).absolutePath}",
                "-DCMAKE_INSTALL_PREFIX=${File(archConfig.installPrefix).absolutePath}",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DBUILD_SHARED_LIBS=ON",
                "-DPLATFORM=${archConfig.name}",
            ).directory(buildDir).runCommand()

            ProcessBuilder("make", "-j${Runtime.getRuntime().availableProcessors()}").directory(buildDir).runCommand()
            ProcessBuilder("make", "install").directory(buildDir).runCommand()
        }
    }

    private fun cloneIfNeeded(dir: File, url: String) {
        if (!dir.exists()) {
            ProcessBuilder("git", "clone", "--depth=1", url, dir.absolutePath).runCommand()
        }
    }

    const val appImageTool = "x64/appimagetool"
    const val appImageRuntime = "x64/runtime"

    fun buildAppImage() {
        ProcessBuilder(
            "wget", "-O", File(appImageTool).absolutePath,
            "https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage"
        ).runCommand()
        ProcessBuilder("chmod", "+x", File(appImageTool).absolutePath).runCommand()
        Config.archConfigs.map { it.cpu }.distinct().forEach {
            ProcessBuilder(
                "wget", "-O", File("$appImageRuntime-$it").absolutePath,
                "https://github.com/AppImage/AppImageKit/releases/download/continuous/runtime-$it",
            ).runCommand()
        }
        Config.archConfigs.forEach { buildAppImage(it) }
    }

    fun buildAppImage(archConfig: Config.ArchConfig) {
        File("${archConfig.name}/root/YoloInfer.desktop").writeText(
            """
                [Desktop Entry]
                Type=Application
                Name=YoloInfer
                Exec=YoloInfer
                Icon=YoloInfer
                Categories=Utility;
                Terminal=true
            """.trimIndent()
        )
        File("${archConfig.name}/root/YoloInfer.png").writeText("")
        when (archConfig) {
            Config.X64 -> {
                File("${archConfig.name}/root/AppRun").apply {
                    writeText(
                        $$"""
                            #!/bin/bash
                            APP_DIR="$(dirname "$(readlink -f "$0")")"
                            LIB_PATH="$APP_DIR/lib:$APP_DIR/usr/lib:$APP_DIR/usr/local/lib"
                            export QT_QPA_PLATFORM_PLUGIN_PATH="$LIB_PATH"
                            LD_LIBRARY_PATH="$LIB_PATH:$LD_LIBRARY_PATH" exec "$APP_DIR/usr/local/bin/YoloInfer" "$@"
                        """.trimIndent()
                    )
                }.let { ProcessBuilder("chmod", "+x", it.absolutePath).runCommand() }
                ProcessBuilder(
                    File(appImageTool).absolutePath,
                    "--runtime-file", File("$appImageRuntime-x86_64").absolutePath,
                    File("${archConfig.name}/root").absolutePath,
                    File("${archConfig.name}/YoloInfer.AppImage").absolutePath,
                ).runCommand()
                println("AppImage created: ${archConfig.name}/YoloInfer.AppImage")
            }
            else -> {
                File("${archConfig.name}/root/lib").mkdirs()
                listOf(
                    "ld-linux-aarch64.so.1",
                    "libc.so.6",
                    "libm.so.6",
                    "libpthread.so.0",
                    "libdl.so.2",
                    "librt.so.1",
                    "libresolv.so.2",
                    "libutil.so.1",
                    "libmvec.so.1",
                    "libstdc++.so.6",
                    "libgcc_s.so.1",
                ).forEach { lib ->
                    ProcessBuilder(
                        "cp", "-L",
                        "/usr/aarch64-linux-gnu/lib/$lib",
                        File("${archConfig.name}/root/lib/$lib").absolutePath,
                    ).runCommand()
                }
                File("${archConfig.name}/root/AppRun").apply {
                    writeText(
                        $$"""
                            #!/bin/bash
                            APP_DIR="$(dirname "$(readlink -f "$0")")"
                            LIB_PATH="$APP_DIR/lib:$APP_DIR/usr/lib:$APP_DIR/usr/local/lib"
                            export QT_QPA_PLATFORM_PLUGIN_PATH="$LIB_PATH"
                            exec "$APP_DIR/lib/ld-linux-aarch64.so.1" --library-path "$LIB_PATH:$LD_LIBRARY_PATH" "$APP_DIR/usr/local/bin/YoloInfer" "$@"
                        """.trimIndent()
                    )
                }.let { ProcessBuilder("chmod", "+x", it.absolutePath).runCommand() }
                ProcessBuilder(
                    File(appImageTool).absolutePath,
                    "--runtime-file", File("$appImageRuntime-aarch64").absolutePath,
                    File("${archConfig.name}/root").absolutePath,
                    File("${archConfig.name}/YoloInfer.AppImage").absolutePath,
                ).apply {
                    environment()["ARCH"] = "aarch64"
                }.runCommand()
                println("AppImage created: ${archConfig.name}/YoloInfer.AppImage")
            }
        }
    }

    fun clean() {
        Config.archConfigs.forEach { archConfig ->
            File(archConfig.name).deleteRecursively()
        }
        println("Clean completed!")
    }
}
