import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

class PackageDownloader {
    private val allVisited = mutableMapOf<String, MutableSet<String>>()

    fun act(archConfig: Config.ArchConfig, originName: String) {
        val providesMap = PackageDatabase.getProvidesMap(archConfig.name)
        val pkgName = providesMap[originName] ?: return

        val archVisited = allVisited.getOrPut(archConfig.name) { mutableSetOf() }
        if (pkgName in archVisited || pkgName in Config.excludeList) return

        archVisited.add(pkgName)
        val pkgInfo = PackageDatabase.findPackageInRepos(archConfig, pkgName) ?: run {
            println("Skip: $pkgName (not found)")
            return
        }

        val deps = downloadAndExtract(archConfig, pkgName, pkgInfo)
        deps.forEach { act(archConfig, it) }
    }

    private fun downloadAndExtract(archConfig: Config.ArchConfig, pkgName: String, pkgInfo: PackageInfo): List<String> {
        val fileName = archConfig.packageFile(pkgName, pkgInfo.version, pkgInfo.arch)
        val url = "${archConfig.mirrorBase(pkgInfo.repo)}/$fileName"
        val deps = mutableListOf<String>()

        println("Download [${archConfig.name}]: $pkgName-${pkgInfo.version}")
        try {
            java.net.URI(url).toURL().openStream().use { input ->
                BufferedInputStream(input).use { bis ->
                    val decompressor = if (fileName.endsWith(".zst")) {
                        ZstdCompressorInputStream(bis)
                    } else {
                        XZCompressorInputStream(bis)
                    }
                    decompressor.use { decomp ->
                        TarArchiveInputStream(decomp).use { tis ->
                            println("Extract [${archConfig.name}]: $pkgName")
                            while (true) {
                                val entry = tis.nextEntry ?: break
                                if (entry.name == ".PKGINFO") {
                                    parsePkgInfo(tis, deps)
                                } else if (!entry.name.startsWith(".")) {
                                    extractFile(archConfig, entry, tis)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("Failed to download/extract $pkgName: ${e.message}")
            throw RuntimeException("Download/Extraction failed")
        }

        return deps
    }

    private fun parsePkgInfo(tis: TarArchiveInputStream, deps: MutableList<String>) {
        val content = tis.readBytes().toString(Charsets.UTF_8)
        content.lines().forEach { line ->
            if (line.startsWith("depend = ")) {
                val dep = line.substringAfter("depend = ")
                    .substringBefore(">").substringBefore("<")
                    .substringBefore("=").trim()
                if (dep.isNotEmpty()) deps.add(dep)
            }
        }
    }

    private fun extractFile(
        archConfig: Config.ArchConfig,
        entry: org.apache.commons.compress.archivers.tar.TarArchiveEntry,
        tis: TarArchiveInputStream
    ) {
        val outFile = File(archConfig.targetDir, entry.name)
        when {
            entry.isSymbolicLink -> {
                outFile.parentFile.mkdirs()
                val outPath = outFile.toPath()
                if (Files.exists(outPath, LinkOption.NOFOLLOW_LINKS)) Files.delete(outPath)
                Files.createSymbolicLink(outPath, Paths.get(entry.linkName))
            }

            entry.isDirectory -> outFile.mkdirs()
            else -> {
                outFile.parentFile.mkdirs()
                outFile.outputStream().use { output ->
                    tis.copyTo(output)
                }
                outFile.setExecutable(entry.mode.and("111".toInt(8)) != 0, false)
            }
        }
    }

    fun downloadAll() {
        Config.archConfigs.forEach { archConfig ->
            println("Starting download for ${archConfig.name}...")
            val root = File(archConfig.targetDir)
            root.mkdirs()

            archConfig.defaultPackages.forEach { pkg -> act(archConfig, pkg) }

            val visitedCount = allVisited[archConfig.name]?.size ?: 0
            println("${archConfig.name} download completed: $visitedCount packages")
        }
    }
}