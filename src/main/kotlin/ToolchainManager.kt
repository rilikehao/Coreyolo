import java.io.File

object ToolchainManager {
    fun createMesonCrossFile(archConfig: Config.ArchConfig) {
        val content = """
            [binaries]
            c = '${archConfig.compilerPrefix}gcc'
            cpp = '${archConfig.compilerPrefix}g++'
            ar = '${archConfig.compilerPrefix}ar'
            strip = '${archConfig.compilerPrefix}strip'
            pkgconfig = 'pkg-config'

            [properties]
            sys_root = '${archConfig.sysrootDir}'
            pkgconfig_libdir = '${getPkgConfigLibDir(archConfig)}'

            [host_machine]
            system = 'linux'
            cpu_family = '${archConfig.cpu}'
            cpu = '${archConfig.cpu}'
            endian = 'little'
        """.trimIndent()

        File(archConfig.toolchainTxt).parentFile.mkdirs()
        File(archConfig.toolchainTxt).writeText(content)
    }

    fun createCmakeToolchainFile(archConfig: Config.ArchConfig) {
        val rootPath = arrayOf(
            archConfig.sysrootDir,
            "${File(archConfig.targetDir).absolutePath}/usr",
            File(archConfig.installPrefix).absolutePath,
        ).joinToString(";")
        val includeFlags = arrayOf(
            "${File(archConfig.targetDir).absolutePath}/usr/include",
            "${File(archConfig.installPrefix).absolutePath}/include",
        ).joinToString(" ") { "-I$it" }
        val linkerFlags = arrayOf(
            "${File(archConfig.targetDir).absolutePath}/usr/lib",
            "${File(archConfig.installPrefix).absolutePath}/lib",
        ).joinToString(" ") { "-L$it" }
        val content = """
            set(CMAKE_SYSTEM_NAME Linux)
            set(CMAKE_SYSTEM_PROCESSOR ${archConfig.cpu})

            set(CMAKE_C_COMPILER ${archConfig.compilerPrefix}gcc)
            set(CMAKE_CXX_COMPILER ${archConfig.compilerPrefix}g++)
            set(CMAKE_SYSROOT "${archConfig.sysrootDir}")
            set(CMAKE_FIND_ROOT_PATH "$rootPath")
            set(CMAKE_PROGRAM_PATH "${File(Config.X64.targetDir).absolutePath}/usr/bin")
            set(CMAKE_CXX_FLAGS "$includeFlags")
            set(CMAKE_EXE_LINKER_FLAGS "$linkerFlags")
            set(CMAKE_SHARED_LINKER_FLAGS "$linkerFlags")

            set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
            set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
            set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
            set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
        """.trimIndent()

        File(archConfig.toolchainCmake).parentFile.mkdirs()
        File(archConfig.toolchainCmake).writeText(content)
    }

    private fun getPkgConfigLibDir(archConfig: Config.ArchConfig) =
        arrayOf(
            "${File(archConfig.targetDir).absolutePath}/usr/lib/pkgconfig",
            "${File(archConfig.installPrefix).absolutePath}/lib/pkgconfig"
        ).joinToString(":")
}
