import SystemUtils.runCommand
import java.io.File

object ProjectBuilder {
    fun buildRGA() {
        ToolchainManager.createCmakeToolchainFile(Config.RK3588)
        val rgaDir = File("rk3588/rkrga")
        cloneIfNeeded(rgaDir, "https://github.com/airockchip/librga.git")
        val targetLibDir = File("rk3588/root/usr/local/lib")
        val targetIncludeDir = File("rk3588/root/usr/local/include/rga")
        targetLibDir.mkdirs()
        targetIncludeDir.mkdirs()

        val sourceLibDir = File("${rgaDir.absolutePath}/libs/Linux/gcc-aarch64")
        val sourceIncludeDir = File("${rgaDir.absolutePath}/include")

        ProcessBuilder("cp", "-r", sourceLibDir.absolutePath + "/.", targetLibDir.absolutePath).runCommand()
        ProcessBuilder("cp", "-r", sourceIncludeDir.absolutePath + "/.", targetIncludeDir.absolutePath).runCommand()

        if (!File("rk3588/rkrga/samples/utils/CMakeLists.txt.backup").exists()) {
            ProcessBuilder(
                "cp",
                "rk3588/rkrga/samples/utils/CMakeLists.txt",
                "rk3588/rkrga/samples/utils/CMakeLists.txt.backup",
            ).runCommand()
            ProcessBuilder(
                "sed", "-i",
                "s/add_library(utils_obj OBJECT \"\")/add_library(utils_obj SHARED \"\")/",
                "rk3588/rkrga/samples/utils/CMakeLists.txt",
            ).runCommand()
        }
        if (!File("rk3588/rkrga/samples/utils/allocator/dma_alloc.cpp.backup").exists()) {
            ProcessBuilder(
                "cp",
                "rk3588/rkrga/samples/utils/allocator/dma_alloc.cpp",
                "rk3588/rkrga/samples/utils/allocator/dma_alloc.cpp.backup",
            ).runCommand()
            ProcessBuilder(
                "sed", "-i",
                "-e", "1i extern \"C\" {",
                "-e", $$"$a }",
                "rk3588/rkrga/samples/utils/allocator/dma_alloc.cpp",
            ).runCommand()
        }
        val rgaBuildDir = File("rk3588/rkrga/build")
        rgaBuildDir.mkdirs()
        ProcessBuilder(
            "/usr/bin/cmake", File("rk3588/rkrga/samples/utils").absolutePath,
            "-DCMAKE_TOOLCHAIN_FILE=${File(Config.RK3588.toolchainCmake).absolutePath}",
            "-DCMAKE_INSTALL_PREFIX=${File(Config.RK3588.installPrefix).absolutePath}",
            "-DCMAKE_BUILD_TYPE=Release",
            "-DBUILD_SHARED_LIBS=ON",
        ).directory(rgaBuildDir).runCommand()
        ProcessBuilder("make", "-j${Runtime.getRuntime().availableProcessors()}").directory(rgaBuildDir).runCommand()

        ProcessBuilder("cp", "-r", File("rk3588/rkrga/build/libutils_obj.so").absolutePath, targetLibDir.absolutePath).runCommand()
        ProcessBuilder("cp", "-r", File("rk3588/rkrga/samples/utils/allocator/include").absolutePath + "/.", targetIncludeDir.absolutePath).runCommand()

        val pc = File("rk3588/root/usr/local/lib/pkgconfig/librga.pc")
        pc.parentFile.mkdirs()
        $$"""
            prefix=$${File("rk3588/root/usr/local").absolutePath}
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
        """.trimIndent().let { File("rk3588/root/usr/local/lib/pkgconfig/librga.pc").writeText(it) }
    }

    fun buildMPP() {
        ToolchainManager.createCmakeToolchainFile(Config.RK3588)
        val mppDir = File("rk3588/rkmpp")
        cloneIfNeeded(mppDir, "https://github.com/rockchip-linux/mpp.git")
        val mppBuildDir = File("rk3588/rkmpp/build")
        mppBuildDir.mkdirs()
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

        val configureArgs = when (archConfig) {
            Config.RK3588 -> arrayOf(
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
            else -> throw Error("不支持的平台")
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

    fun buildZLMediaKit() {
        Config.archConfigs.forEach { archConfig ->
            ToolchainManager.createCmakeToolchainFile(archConfig)
            val zLMediaKitDir = File("${archConfig.name}/ZLMediaKit")
            cloneIfNeeded(zLMediaKitDir, "https://github.com/ZLMediaKit/ZLMediaKit.git")

            ProcessBuilder(
                "git",  "submodule", "update", "--init",
            ).directory(zLMediaKitDir).runCommand()

            val buildDir = File("${archConfig.name}/ZLMediaKit/build")
            buildDir.mkdirs()

            ProcessBuilder(
                "/usr/bin/cmake", zLMediaKitDir.absolutePath,
                "-DCMAKE_TOOLCHAIN_FILE=${File(archConfig.toolchainCmake).absolutePath}",
                "-DCMAKE_INSTALL_PREFIX=${File(archConfig.installPrefix).absolutePath}",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
                "-DENABLE_OBJCOPY=no",
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

    const val APP_IMAGE_TOOL = "x64/appimagetool"
    const val APP_IMAGE_RUNTIME = "x64/runtime"

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
        File("${archConfig.name}/root/config.ini").writeText(
            """
                ; auto-generated by mINI class {
                
                [api]
                apiDebug=1
                defaultSnap=./www/logo.png
                downloadRoot=./www
                secret=21344657
                snapRoot=./www/snap/
                
                [cluster]
                origin_url=
                retry_count=3
                timeout_sec=15
                
                [ffmpeg]
                bin=/usr/bin/ffmpeg
                cmd=%s -re -i %s -c:a aac -strict -2 -ar 44100 -ab 48k -c:v libx264 -f flv %s
                log=./ffmpeg/ffmpeg.log
                restart_sec=0
                snap=%s -i %s -y -f mjpeg -frames:v 1 -an %s
                
                [general]
                broadcast_player_count_changed=0
                check_nvidia_dev=1
                enableVhost=0
                enable_ffmpeg_log=0
                flowThreshold=1024
                listen_ip=::
                maxStreamWaitMS=15000
                mediaServerId=your_server_id
                mergeWriteMS=0
                resetWhenRePlay=1
                streamNoneReaderDelayMS=20000
                unready_frame_cache=100
                wait_add_track_ms=3000
                wait_audio_track_data_ms=1000
                wait_track_ready_ms=10000
                
                [hls]
                broadcastRecordTs=0
                deleteDelaySec=10
                fastRegister=0
                fileBufSize=65536
                segDelay=0
                segDur=2
                segKeep=0
                segNum=3
                segRetain=5
                
                [hook]
                alive_interval=10.0
                enable=0
                on_flow_report=
                on_http_access=
                on_play=
                on_publish=
                on_record_mp4=
                on_record_ts=
                on_rtp_server_timeout=
                on_rtsp_auth=
                on_rtsp_realm=
                on_send_rtp_stopped=
                on_server_exited=
                on_server_keepalive=
                on_server_started=
                on_shell_login=
                on_stream_changed=
                on_stream_none_reader=
                on_stream_not_found=
                retry=1
                retry_delay=3.0
                stream_changed_schemas=rtsp/rtmp/fmp4/ts/hls/hls.fmp4
                timeoutSec=10
                
                [http]
                allow_cross_domains=1
                allow_ip_range=::1,127.0.0.1,172.16.0.0-172.31.255.255,192.168.0.0-192.168.255.255,10.0.0.0-10.255.255.255
                charSet=utf-8
                dirMenu=1
                forbidCacheSuffix=
                forwarded_ip_header=
                keepAliveSecond=30
                maxReqSize=40960
                notFound=<html><head><title>404 Not Found</title></head><body bgcolor="white"><center><h1>您访问的资源不存在！</h1></center><hr><center>ZLMediaKit(git hash:588d9de/%aI,branch:,build time:2025-10-11T11:20:01)</center></body></html>
                port=50080
                rootPath=./www
                sendBufSize=65536
                sslport=50443
                virtualPath=
                
                [multicast]
                addrMax=239.255.255.255
                addrMin=239.0.0.0
                udpTTL=64
                
                [protocol]
                add_mute_audio=1
                auto_close=0
                continue_push_ms=15000
                enable_audio=1
                enable_fmp4=1
                enable_hls=0
                enable_hls_fmp4=0
                enable_mp4=0
                enable_rtmp=1
                enable_rtsp=1
                enable_ts=0
                fmp4_demand=0
                hls_demand=0
                hls_save_path=./www
                modify_stamp=2
                mp4_as_player=0
                mp4_max_second=3600
                mp4_save_path=./www
                paced_sender_ms=0
                rtmp_demand=0
                rtsp_demand=0
                ts_demand=0
                
                [record]
                appName=record
                enableFmp4=0
                fastStart=0
                fileBufSize=65536
                fileRepeat=0
                sampleMS=500
                
                [rtc]
                signalingPort=53000
                signalingSslPort=53001
                icePort=53478
                iceTcpPort=53478
                bfilter=0
                datachannel_echo=1
                externIP=
                maxRtpCacheMS=5000
                maxRtpCacheSize=2048
                max_bitrate=0
                min_bitrate=0
                nackIntervalRatio=1.0
                nackMaxCount=15
                nackMaxMS=3000
                nackMaxSize=2048
                nackRtpSize=8
                port=58000
                preferredCodecA=PCMA,PCMU,opus,mpeg4-generic
                preferredCodecV=H264,H265,AV1,VP9,VP8
                rembBitRate=0
                start_bitrate=0
                tcpPort=58000
                timeoutSec=15
                
                [rtmp]
                directProxy=1
                enhanced=0
                handshakeSecond=15
                keepAliveSecond=15
                port=51935
                sslport=0
                
                [rtp]
                audioMtuSize=600
                h264_stap_a=1
                lowLatency=0
                rtpMaxSize=10
                videoMtuSize=1400
                
                [rtp_proxy]
                dumpDir=
                gop_cache=1
                h264_pt=98
                h265_pt=99
                merge_frame=1
                opus_pt=100
                port=50000
                port_range=30000-35000
                ps_pt=96
                rtp_g711_dur_ms=100
                timeoutSec=15
                udp_recv_socket_buffer=4194304
                
                [rtsp]
                authBasic=0
                directProxy=1
                handshakeSecond=15
                keepAliveSecond=15
                lowLatency=0
                port=50554
                rtpTransportType=-1
                sslport=0
                
                [shell]
                maxReqSize=1024
                port=0
                
                [srt]
                latencyMul=4
                passPhrase=
                pktBufSize=8192
                port=59000
                timeoutSec=5
                
                ; } ---
            """.trimIndent()
        )
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
                            LIB_PATH="$APP_DIR/lib:$APP_DIR/usr/lib:$APP_DIR/usr/local/lib:$APP_DIR/usr/lib/libproxy"
                            LD_LIBRARY_PATH="$LIB_PATH:$LD_LIBRARY_PATH" "$APP_DIR/usr/local/bin/MediaServer" --config "$APP_DIR/config.ini" --log-dir /tmp/log-MediaServer &
                            PID_MEDIA_SERVER=$!
                            export QT_QPA_PLATFORM=offscreen
                            export QT_QPA_PLATFORM_PLUGIN_PATH="$LIB_PATH"
                            until LD_LIBRARY_PATH="$LIB_PATH:$LD_LIBRARY_PATH" "$APP_DIR/usr/local/bin/YoloInfer" "$@"; do
                                echo "YoloInfer failed with exit code $EXIT_CODE, restart..."
                                sleep 5
                            done
                        """.trimIndent()
                    )
                }.let { ProcessBuilder("chmod", "+x", it.absolutePath).runCommand() }
                ProcessBuilder(
                    File(APP_IMAGE_TOOL).absolutePath,
                    "--runtime-file", File("$APP_IMAGE_RUNTIME-x86_64").absolutePath,
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
                            LIB_PATH="$APP_DIR/lib:$APP_DIR/usr/lib:$APP_DIR/usr/local/lib:$APP_DIR/usr/lib/libproxy"
                            "$APP_DIR/lib/ld-linux-aarch64.so.1" --library-path "$LIB_PATH:$LD_LIBRARY_PATH" "$APP_DIR/usr/local/bin/MediaServer" --config "$APP_DIR/config.ini" --log-dir /tmp/log-MediaServer &
                            PID_MEDIA_SERVER=$!
                            export QT_QPA_PLATFORM=offscreen
                            export QT_QPA_PLATFORM_PLUGIN_PATH="$LIB_PATH"
                            until "$APP_DIR/lib/ld-linux-aarch64.so.1" --library-path "$LIB_PATH:$LD_LIBRARY_PATH" "$APP_DIR/usr/local/bin/YoloInfer" "$@"; do
                                echo "YoloInfer failed with exit code $EXIT_CODE, restart..."
                                sleep 5
                            done
                            kill $PID_MEDIA_SERVER
                        """.trimIndent()
                    )
                }.let { ProcessBuilder("chmod", "+x", it.absolutePath).runCommand() }
                ProcessBuilder(
                    File(APP_IMAGE_TOOL).absolutePath,
                    "--runtime-file", File("$APP_IMAGE_RUNTIME-aarch64").absolutePath,
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
