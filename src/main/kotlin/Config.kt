object Config {
    data class ArchConfig(
        val name: String,
        val mirrorBase: (String) -> String,
        val packageFile: (String, String, String) -> String,
        val compilerPrefix: String,
        val cpu: String,
        val targetArch: String,
        val sysrootDir: String,
        val targetDir: String,
        val toolchainTxt: String,
        val toolchainCmake: String,
        val installPrefix: String,
        val defaultPackages: List<String>,
    )

    val RK3588 = ArchConfig(
        name = "rk3588",
        mirrorBase = { repo -> "http://ca.us.mirror.archlinuxarm.org/aarch64/$repo" },
        packageFile = { pkgName, version, arch -> "$pkgName-$version-$arch.pkg.tar.xz" },
        compilerPrefix = "aarch64-linux-gnu-",
        cpu = "aarch64",
        targetArch = "aarch64-linux-gnu",
        sysrootDir = "/usr/aarch64-linux-gnu",
        targetDir = "rk3588/root",
        toolchainTxt = "rk3588/toolchain.txt",
        toolchainCmake = "rk3588/toolchain.cmake",
        installPrefix = "rk3588/root/usr/local",
        defaultPackages = listOf(
            "lua",
            "qt6-base",
            "v4l-utils",
            "openssl",
            "libsrtp",
            "libdrm",
        ),
    )

    val X64 = ArchConfig(
        name = "x64",
        mirrorBase = { repo -> "http://mirrors.ocf.berkeley.edu/archlinux/$repo/os/x86_64" },
        packageFile = { pkgName, version, arch -> "$pkgName-$version-$arch.pkg.tar.zst" },
        compilerPrefix = "",
        cpu = "x86_64",
        targetArch = "x86_64-linux-gnu",
        sysrootDir = "/",
        targetDir = "x64/root",
        toolchainTxt = "x64/toolchain.txt",
        toolchainCmake = "x64/toolchain.cmake",
        installPrefix = "x64/root/usr/local",
        defaultPackages = listOf(
            "lua",
            "qt6-base",
            "v4l-utils",
            "openssl",
            "libsrtp",
            "ffmpeg",
            "vulkan-icd-loader",
        ),
    )

    val archConfigs = listOf(RK3588, X64)
    val excludeList = setOf("glibc", "gcc-libs", "linux-api-headers")
    val repos = listOf("core", "extra")
}
