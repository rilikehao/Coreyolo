import java.io.File

object ToolchainManager {
    fun createCmakeToolchainFile(archConfig: Config.ArchConfig) {
        val rootPath = arrayOf(
            archConfig.sysrootDir,
            "${File(archConfig.targetDir()).absolutePath}/usr",
            File(archConfig.installPrefix()).absolutePath,
        ).joinToString(";")
        val includeFlags = mutableListOf(
            "${File(archConfig.targetDir()).absolutePath}/usr/include",
            "${File(archConfig.installPrefix()).absolutePath}/include",
            "${File(archConfig.cpu).absolutePath}/nnrt/latest/aarch64-linux/include",
        ).joinToString(" ") { "-I$it" }
        val linkerFlags = mutableListOf(
            "${File(archConfig.targetDir()).absolutePath}/usr/lib",
            "${File(archConfig.installPrefix()).absolutePath}/lib",
            "${File(archConfig.cpu).absolutePath}/nnrt/latest/aarch64-linux/lib64",
            "${File(archConfig.cpu).absolutePath}/nnrt/latest/aarch64-linux/devlib",
        ).joinToString(" ") { "-L$it -Wl,-rpath-link=$it" }
        val content = """
            set(CMAKE_SYSTEM_NAME Linux)
            set(CMAKE_SYSTEM_PROCESSOR ${archConfig.cpu})

            set(CMAKE_C_COMPILER ${archConfig.compilerPrefix}gcc)
            set(CMAKE_CXX_COMPILER ${archConfig.compilerPrefix}g++)
            set(CMAKE_SYSROOT "${File(archConfig.targetDir()).absolutePath}")
            set(CMAKE_FIND_ROOT_PATH "$rootPath")
            set(CMAKE_PROGRAM_PATH "${File(Config.x86_64.targetDir()).absolutePath}/usr/bin")
            set(CMAKE_CXX_FLAGS "$includeFlags")
            set(CMAKE_EXE_LINKER_FLAGS "$linkerFlags")
            set(CMAKE_SHARED_LINKER_FLAGS "$linkerFlags")

            set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
            set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
            set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
            set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
        """.trimIndent()

        File(archConfig.toolchain()).parentFile.mkdirs()
        File(archConfig.toolchain()).writeText(content)
    }
}
