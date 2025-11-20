import SystemUtils.runCommand
import java.io.File

object ProjectBuilder {
    fun buildRGA() {
        ToolchainManager.createCmakeToolchainFile(Config.aarch64)
        val rgaDir = File("aarch64/rkrga")
        cloneIfNeeded(rgaDir, "https://github.com/airockchip/librga.git")
        val targetLibDir = File("aarch64/root/usr/local/lib")
        val targetIncludeDir = File("aarch64/root/usr/local/include/rga")
        targetLibDir.mkdirs()
        targetIncludeDir.mkdirs()

        val sourceLibDir = File("${rgaDir.absolutePath}/libs/Linux/gcc-aarch64")
        val sourceIncludeDir = File("${rgaDir.absolutePath}/include")

        ProcessBuilder("cp", "-r", sourceLibDir.absolutePath + "/.", targetLibDir.absolutePath).runCommand()
        ProcessBuilder("cp", "-r", sourceIncludeDir.absolutePath + "/.", targetIncludeDir.absolutePath).runCommand()

        if (!File("aarch64/rkrga/samples/utils/CMakeLists.txt.backup").exists()) {
            ProcessBuilder(
                "cp",
                "aarch64/rkrga/samples/utils/CMakeLists.txt",
                "aarch64/rkrga/samples/utils/CMakeLists.txt.backup",
            ).runCommand()
            ProcessBuilder(
                "sed", "-i",
                "s/add_library(utils_obj OBJECT \"\")/add_library(utils_obj SHARED \"\")/",
                "aarch64/rkrga/samples/utils/CMakeLists.txt",
            ).runCommand()
        }
        if (!File("aarch64/rkrga/samples/utils/allocator/dma_alloc.cpp.backup").exists()) {
            ProcessBuilder(
                "cp",
                "aarch64/rkrga/samples/utils/allocator/dma_alloc.cpp",
                "aarch64/rkrga/samples/utils/allocator/dma_alloc.cpp.backup",
            ).runCommand()
            ProcessBuilder(
                "sed", "-i",
                "-e", "1i extern \"C\" {",
                "-e", $$"$a }",
                "aarch64/rkrga/samples/utils/allocator/dma_alloc.cpp",
            ).runCommand()
        }
        val rgaBuildDir = File("aarch64/rkrga/build")
        rgaBuildDir.mkdirs()
        ProcessBuilder(
            "/usr/bin/cmake", File("aarch64/rkrga/samples/utils").absolutePath,
            "-DCMAKE_TOOLCHAIN_FILE=${File(Config.aarch64.toolchain()).absolutePath}",
            "-DCMAKE_INSTALL_PREFIX=${File(Config.aarch64.installPrefix()).absolutePath}",
            "-DCMAKE_BUILD_TYPE=Release",
            "-DBUILD_SHARED_LIBS=ON",
        ).directory(rgaBuildDir).runCommand()
        ProcessBuilder("make", "-j${Runtime.getRuntime().availableProcessors()}").directory(rgaBuildDir).runCommand()

        ProcessBuilder(
            "cp", "-r",
            File("aarch64/rkrga/build/libutils_obj.so").absolutePath,
            targetLibDir.absolutePath
        ).runCommand()
        ProcessBuilder(
            "cp", "-r",
            File("aarch64/rkrga/samples/utils/allocator/include").absolutePath + "/.",
            targetIncludeDir.absolutePath
        ).runCommand()

        val pc = File("aarch64/root/usr/local/lib/pkgconfig/librga.pc")
        pc.parentFile.mkdirs()
        $$"""
            prefix=$${File("aarch64/root/usr/local").absolutePath}
            exec_prefix=${prefix}
            libdir=${prefix}/lib
            includedir=${prefix}/include
            
            Name: librga
            Description: Rockchip RGA
            Requires.private:
            Version: 0.0.0
            Libs: -L${libdir} -lrga
            Libs.private:
            Cflags: -I${includedir}
        """.trimIndent().let { File("aarch64/root/usr/local/lib/pkgconfig/librga.pc").writeText(it) }
    }

    fun buildMPP() {
        ToolchainManager.createCmakeToolchainFile(Config.aarch64)
        val mppDir = File("aarch64/rkmpp")
        cloneIfNeeded(mppDir, "https://github.com/rockchip-linux/mpp.git")
        val mppBuildDir = File("aarch64/rkmpp/build")
        mppBuildDir.mkdirs()
        ProcessBuilder(
            "/usr/bin/cmake", mppDir.absolutePath,
            "-DCMAKE_TOOLCHAIN_FILE=${File(Config.aarch64.toolchain()).absolutePath}",
            "-DCMAKE_INSTALL_PREFIX=${File(Config.aarch64.installPrefix()).absolutePath}",
            "-DCMAKE_BUILD_TYPE=Release",
            "-DBUILD_SHARED_LIBS=ON",
            "-DBUILD_TEST=OFF",
        ).directory(mppBuildDir).runCommand()
        ProcessBuilder("make", "-j${Runtime.getRuntime().availableProcessors()}").directory(mppBuildDir).runCommand()
        ProcessBuilder("make", "install").directory(mppBuildDir).runCommand()
    }

    fun buildRKNPU2() {
        val rknpu2Dir = File("aarch64/rknn-toolkit2")
        cloneIfNeeded(rknpu2Dir, "https://github.com/airockchip/rknn-toolkit2.git")

        val targetLibDir = File("aarch64/root/usr/local/lib")
        val targetIncludeDir = File("aarch64/root/usr/local/include")
        targetLibDir.mkdirs()
        targetIncludeDir.mkdirs()

        val sourceLibDir = File("${rknpu2Dir.absolutePath}/rknpu2/runtime/Linux/librknn_api/aarch64")
        val sourceIncludeDir = File("${rknpu2Dir.absolutePath}/rknpu2/runtime/Linux/librknn_api/include")

        ProcessBuilder("cp", "-r", sourceLibDir.absolutePath + "/.", targetLibDir.absolutePath).runCommand()
        ProcessBuilder("cp", "-r", sourceIncludeDir.absolutePath + "/.", targetIncludeDir.absolutePath).runCommand()
    }

    fun buildMNN() {
        val mnnDir = File("x86_64/MNN")
        cloneIfNeeded(mnnDir, "https://github.com/alibaba/MNN.git")
        ProcessBuilder("git", "fetch", "--tags").directory(mnnDir).runCommand()
        ProcessBuilder("git", "checkout", "3.2.5").directory(mnnDir).runCommand()

        val mnnBuildDir = File("x86_64/MNN/build")
        mnnBuildDir.mkdirs()

        ProcessBuilder(
            "/usr/bin/cmake", mnnDir.absolutePath,
            "-DCMAKE_INSTALL_PREFIX=${File(Config.x86_64.installPrefix()).absolutePath}",
            "-DCMAKE_BUILD_TYPE=Release",
            "-DBUILD_SHARED_LIBS=ON",
            "-DMNN_BUILD_TOOLS=ON",
            "-DMNN_BUILD_QUANTOOLS=ON",
            "-DMNN_BUILD_CONVERTER=ON",
            "-DMNN_VULKAN=ON",
        ).directory(mnnBuildDir).runCommand()

        ProcessBuilder("make", "-j${Runtime.getRuntime().availableProcessors()}").directory(mnnBuildDir).runCommand()
        ProcessBuilder("make", "install").directory(mnnBuildDir).runCommand()

        val vulkanLib = File("x86_64/MNN/build/source/backend/vulkan/libMNN_Vulkan.so")
        val convertDepsLib = File("x86_64/MNN/build/tools/converter/libMNNConvertDeps.so")
        val trainLib = File("x86_64/MNN/build/tools/train/libMNNTrain.so")
        val trainUtilsLib = File("x86_64/MNN/build/tools/train/libMNNTrainUtils.so")
        val targetLibDir = File("x86_64/root/usr/local/lib")

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
        val sourceBuildDir = File("x86_64/MNN/build")
        val targetBinDir = File("x86_64/root/usr/local/bin")
        targetBinDir.mkdirs()

        tools.forEach { tool ->
            val toolFile = File(sourceBuildDir, tool)
            if (toolFile.exists()) {
                ProcessBuilder("cp", toolFile.absolutePath, targetBinDir.absolutePath).runCommand()
            }
        }
    }

    fun buildACL() {
        File("aarch64").mkdirs()
        listOf(
            "Ascend-cann-nnrt_6.0.1_linux-aarch64.run",
        ).forEach {
            if (!File("aarch64/$it").exists()) {
                ProcessBuilder(
                    "curl", "-o", File("aarch64/$it").absolutePath,
                    "https://f000.kw92.cyou/file/kunweiz92-YoloInfer/$it",
                ).runCommand()
            }
            ProcessBuilder("chmod", "+x", File("aarch64/$it").absolutePath).runCommand()
            ProcessBuilder(
                "${File("aarch64/$it").absolutePath}",
                "--quiet", "--nox11", "--install", "--install-path=${File("aarch64/root/usr/local").absolutePath}",
            ).runCommand()
        }
        ProcessBuilder("chmod", "-R", "+w", File("aarch64/root/usr/local/nnrt").absolutePath).runCommand()
        ProcessBuilder("rm", "-rf", "${System.getProperty("user.home")}/Ascend").runCommand()
        ProcessBuilder(
            "curl", "-o", File("aarch64/root/usr/local/nnrt/latest/include/acl/ops/acl_dvpp.h").absolutePath,
            "https://f000.kw92.cyou/file/kunweiz92-YoloInfer/acl_dvpp.h",
        ).runCommand()
    }

    fun buildFFmpegRockchip() {
        val ffmpegDir = File("aarch64/ffmpeg-rockchip")
        cloneIfNeeded(ffmpegDir, "https://github.com/nyanmisaka/ffmpeg-rockchip.git")

        val configureArgs = arrayOf(
            "./configure",
            "--prefix=${File(Config.aarch64.installPrefix()).absolutePath}/rockchip",
            "--arch=arm64",
            "--target-os=linux",
            "--cross-prefix=${Config.aarch64.compilerPrefix}",
            "--sysroot=${Config.aarch64.sysrootDir}",
            "--pkg-config=pkg-config",
            "--extra-cflags=${
                arrayOf(
                    "${File(Config.aarch64.installPrefix()).absolutePath}/include",
                    "${File(Config.aarch64.targetDir()).absolutePath}/usr/include"
                ).joinToString(" ") { "-I$it" }
            }",
            "--extra-ldflags=${
                arrayOf(
                    "${File(Config.aarch64.installPrefix()).absolutePath}/lib",
                    "${File(Config.aarch64.targetDir()).absolutePath}/usr/lib"
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

        ProcessBuilder(*configureArgs).apply {
            environment()["PKG_CONFIG_LIBDIR"] = arrayOf(
                "${File(Config.aarch64.installPrefix()).absolutePath}/lib/pkgconfig",
                "${File(Config.aarch64.targetDir()).absolutePath}/usr/lib/pkgconfig",
            ).joinToString(":")
        }.directory(ffmpegDir).runCommand()
        ProcessBuilder("make", "-j${Runtime.getRuntime().availableProcessors()}").directory(ffmpegDir).runCommand()
        ProcessBuilder("make", "install").directory(ffmpegDir).runCommand()
    }

    fun buildFFmpegAscend() {
        val ffmpegDir = File("aarch64/ffmpeg")
        cloneIfNeeded(ffmpegDir, "https://git.ffmpeg.org/ffmpeg.git")

        val configureArgs = arrayOf(
            "./configure",
            "--prefix=${File(Config.aarch64.installPrefix()).absolutePath}/ascend",
            "--arch=arm64",
            "--target-os=linux",
            "--cross-prefix=${Config.aarch64.compilerPrefix}",
            "--sysroot=${Config.aarch64.sysrootDir}",
            "--pkg-config=pkg-config",
            "--extra-cflags=${
                arrayOf(
                    "${File(Config.aarch64.installPrefix()).absolutePath}/include",
                    "${File(Config.aarch64.targetDir()).absolutePath}/usr/include"
                ).joinToString(" ") { "-I$it" }
            }",
            "--extra-ldflags=${
                arrayOf(
                    "${File(Config.aarch64.installPrefix()).absolutePath}/lib",
                    "${File(Config.aarch64.targetDir()).absolutePath}/usr/lib"
                ).joinToString(" ") { "-L$it" }
            }",
            "--enable-gpl",
            "--enable-version3",
            "--enable-libx264",
            "--enable-libx265",
            "--enable-shared",
            "--disable-static",
            "--disable-stripping",
            "--disable-doc",
        )

        ProcessBuilder(*configureArgs).apply {
            environment()["PKG_CONFIG_LIBDIR"] = arrayOf(
                "${File(Config.aarch64.installPrefix()).absolutePath}/lib/pkgconfig",
                "${File(Config.aarch64.targetDir()).absolutePath}/usr/lib/pkgconfig",
            ).joinToString(":")
        }.directory(ffmpegDir).runCommand()
        ProcessBuilder("make", "-j${Runtime.getRuntime().availableProcessors()}").directory(ffmpegDir).runCommand()
        ProcessBuilder("make", "install").directory(ffmpegDir).runCommand()
    }

    fun buildFFmpeg() {
        buildFFmpegRockchip()
        buildFFmpegAscend()
    }

    fun buildNative() {
        Config.archConfigs.forEach { archConfig ->
            archConfig.platform.forEach { platform ->
                ToolchainManager.createCmakeToolchainFile(archConfig)
                val nativeDir = File("native")
                val buildDir = File("${archConfig.cpu}/native/build-$platform")
                buildDir.mkdirs()

                ProcessBuilder(
                    "/usr/bin/cmake", nativeDir.absolutePath,
                    "-DCMAKE_TOOLCHAIN_FILE=${File(archConfig.toolchain()).absolutePath}",
                    "-DCMAKE_INSTALL_PREFIX=${File(archConfig.installPrefix()).absolutePath}/$platform",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_SHARED_LIBS=ON",
                    "-DPLATFORM=$platform",
                ).directory(buildDir).runCommand()

                ProcessBuilder("make", "-j${Runtime.getRuntime().availableProcessors()}").directory(buildDir)
                    .runCommand()
                ProcessBuilder("make", "install").directory(buildDir).runCommand()
            }
        }
    }

    private fun cloneIfNeeded(dir: File, url: String) {
        if (!dir.exists()) {
            ProcessBuilder("git", "clone", "--depth=1", url, dir.absolutePath).runCommand()
        }
    }

    const val APP_IMAGE_TOOL = "x86_64/appimagetool"
    const val APP_IMAGE_RUNTIME = "x86_64/runtime"

    fun buildAppImage() {
        if (!File(APP_IMAGE_TOOL).exists()) {
            ProcessBuilder(
                "wget", "-O", File(APP_IMAGE_TOOL).absolutePath,
                "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage"
            ).runCommand()
            ProcessBuilder("chmod", "+x", File(APP_IMAGE_TOOL).absolutePath).runCommand()
        }
        Config.archConfigs.map { it.cpu }.distinct().forEach {
            if (!File("$APP_IMAGE_RUNTIME-$it").exists()) {
                ProcessBuilder(
                    "wget", "-O", File("$APP_IMAGE_RUNTIME-$it").absolutePath,
                    "https://github.com/AppImage/type2-runtime/releases/download/continuous/runtime-$it",
                ).runCommand()
            }
        }
        Config.archConfigs.forEach { buildAppImage(it) }
    }

    fun buildAppImage(archConfig: Config.ArchConfig) {
        File("${archConfig.cpu}/root/YoloInfer.desktop").writeText(
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
        File("${archConfig.cpu}/root/YoloInfer.png").writeText("")
        if (archConfig == Config.aarch64) {
            File("aarch64/root/lib").mkdirs()
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
                    File("aarch64/root/lib/$lib").absolutePath,
                ).runCommand()
            }
        }
        File("${archConfig.cpu}/root/AppRun").apply { writeText(archConfig.script) }
            .let { ProcessBuilder("chmod", "+x", it.absolutePath).runCommand() }
        ProcessBuilder(
            File(APP_IMAGE_TOOL).absolutePath,
            "--runtime-file", File("$APP_IMAGE_RUNTIME-${archConfig.cpu}").absolutePath,
            File("${archConfig.cpu}/root").absolutePath,
            File("${archConfig.cpu}/YoloInfer.AppImage").absolutePath,
        ).apply { environment()["ARCH"] = archConfig.cpu }.runCommand()
        println("AppImage created: ${archConfig.cpu}/YoloInfer.AppImage")
    }

    fun clean() {
        Config.archConfigs.forEach { archConfig -> File(archConfig.cpu).deleteRecursively() }
        println("Clean completed!")
    }
}
