object Config {
    data class ArchConfig(
        val mirrorBase: (String) -> String,
        val packageFile: (String, String, String) -> String,
        val compilerPrefix: String,
        val cpu: String,
        val sysrootDir: String,
        val packages: List<String>,
        val script: String,
        val platform: List<String>,
    ) {
        fun targetDir() = "$cpu/root"
        fun toolchain() = "$cpu/toolchain.cmake"
        fun installPrefix() = "$cpu/root/usr/local"
        fun defaultPackages() = packages + listOf(
            "lua",
            "qt6-base",
            "v4l-utils",
            "openssl",
            "x264",
            "x265",
        )
    }

    val aarch64 = ArchConfig(
        mirrorBase = { repo -> "http://ca.us.mirror.archlinuxarm.org/aarch64/$repo" },
        packageFile = { pkgName, version, arch -> "$pkgName-$version-$arch.pkg.tar.xz" },
        compilerPrefix = "aarch64-linux-gnu-",
        cpu = "aarch64",
        sysrootDir = "/usr/aarch64-linux-gnu",
        packages = listOf("libdrm"),
        platform = listOf("rockchip", "ascend"),
        script = $$"""
            #!/bin/bash
            APP_DIR="$(dirname "$(readlink -f "$0")")"
            PLATFORM="$1"
            shift
            SUFFIX="$1"
            shift
            LIB_PATH="$APP_DIR/lib:$APP_DIR/usr/lib:$APP_DIR/usr/local/lib:$APP_DIR/usr/lib/libproxy:$APP_DIR/usr/local/$PLATFORM/lib:$APP_DIR/usr/local/$PLATFORM$SUFFIX/lib"
            export QT_QPA_PLATFORM=offscreen
            export QT_QPA_PLATFORM_PLUGIN_PATH="$LIB_PATH"
            exec "$APP_DIR/lib/ld-linux-aarch64.so.1" --library-path "$LIB_PATH:$LD_LIBRARY_PATH" "$APP_DIR/usr/local/bin/YoloInfer-aarch64-$PLATFORM$SUFFIX" "$@"
        """.trimIndent()
    )

    val x86_64 = ArchConfig(
        mirrorBase = { repo -> "http://mirrors.ocf.berkeley.edu/archlinux/$repo/os/x86_64" },
        packageFile = { pkgName, version, arch -> "$pkgName-$version-$arch.pkg.tar.zst" },
        compilerPrefix = "",
        cpu = "x86_64",
        sysrootDir = "/",
        packages = listOf("vulkan-icd-loader", "ffmpeg"),
        platform = listOf("software"),
        script = $$"""
            #!/bin/bash
            APP_DIR="$(dirname "$(readlink -f "$0")")"
            PLATFORM="$1"
            shift
            LIB_PATH="$APP_DIR/lib:$APP_DIR/usr/lib:$APP_DIR/usr/local/lib:$APP_DIR/usr/lib/libproxy:$APP_DIR/usr/local/$PLATFORM/lib:$APP_DIR/usr/local/$PLATFORM$SUFFIX/lib"
            export QT_QPA_PLATFORM=offscreen
            export QT_QPA_PLATFORM_PLUGIN_PATH="$LIB_PATH"
            LD_LIBRARY_PATH="$LIB_PATH:$LD_LIBRARY_PATH" exec "$APP_DIR/usr/local/bin/YoloInfer-x86_64-$PLATFORM" "$@"
        """.trimIndent()
    )

    val archConfigs = listOf(aarch64, x86_64)
    val excludeList = setOf("glibc", "gcc-libs", "linux-api-headers")
    val repos = listOf("core", "extra")
}
