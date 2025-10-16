import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File

object PackageDatabase {
    private val providesMaps = mutableMapOf<String, Map<String, String>>()

    fun getProvidesMap(archName: String): Map<String, String> {
        return providesMaps.getOrPut(archName) {
            buildProvidesMap(archName)
        }
    }

    private fun buildProvidesMap(archName: String): Map<String, String> {
        val archConfig = Config.archConfigs.find { it.name == archName } ?: return emptyMap()
        val map = mutableMapOf<String, String>()

        Config.repos.forEach { repo ->
            parseDbFile(archConfig, repo, "desc") { _, realPkgName ->
                map[realPkgName] = realPkgName
            }
        }

        Config.repos.forEach { repo ->
            val dbFile = getRepoDatabase(archConfig, repo)
            println("  Parsing $repo.db (${dbFile.length() / 1024}KB)...")
            var count = 0
            parseDbFile(archConfig, repo, "depends") { tis, realPkgName ->
                val content = tis.readBytes().toString(Charsets.UTF_8)
                val lines = content.lines()
                var i = 0
                while (i < lines.size) {
                    if (lines[i] == "%PROVIDES%") {
                        i++
                        while (i < lines.size && !lines[i].startsWith("%")) {
                            val provide =
                                lines[i].substringBefore("=").substringBefore(">").substringBefore("<").trim()
                            if (provide.isNotEmpty()) {
                                map.putIfAbsent(provide, realPkgName)
                                count++
                            }
                            i++
                        }
                        break
                    }
                    i++
                }
            }
            if (count > 0) println("    -> $count provides")
        }
        return map
    }

    fun getRepoDatabase(archConfig: Config.ArchConfig, repo: String): File {
        val dbDir = File(archConfig.name)
        dbDir.mkdirs()
        val dbFile = File(dbDir, "$repo.db")
        if (!dbFile.exists()) {
            val url = "${archConfig.mirrorBase(repo)}/$repo.db"
            java.net.URI(url).toURL().openStream().use { input ->
                dbFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return dbFile
    }

    fun parseDbFile(archConfig: Config.ArchConfig, repo: String, entryType: String, processor: (TarArchiveInputStream, String) -> Unit) {
        val dbFile = getRepoDatabase(archConfig, repo)
        dbFile.inputStream().use { fis ->
            BufferedInputStream(fis).use { bis ->
                GzipCompressorInputStream(bis).use { gzis ->
                    TarArchiveInputStream(gzis).use { tis ->
                        while (true) {
                            val entry = tis.nextEntry ?: break
                            if (entry.name.endsWith("/$entryType")) {
                                val dirName = entry.name.substringBeforeLast("/")
                                val nameWithVersion = dirName.substringBeforeLast("-")
                                val realPkgName = nameWithVersion.substringBeforeLast("-")
                                processor(tis, realPkgName)
                            }
                        }
                    }
                }
            }
        }
    }

    fun findPackageInRepos(archConfig: Config.ArchConfig, pkgName: String): PackageInfo? {
        for (repo in Config.repos) {
            try {
                parseDbFile(archConfig, repo, "desc") { tis, realPkgName ->
                    if (realPkgName == pkgName) {
                        val content = tis.readBytes().toString(Charsets.UTF_8)
                        val lines = content.lines()
                        var version = ""
                        var pkgArch = ""
                        for (i in lines.indices) {
                            when (lines[i]) {
                                "%VERSION%" -> version = lines.getOrNull(i + 1) ?: ""
                                "%ARCH%" -> pkgArch = lines.getOrNull(i + 1) ?: ""
                            }
                        }
                        if (version.isNotEmpty() && pkgArch.isNotEmpty()) {
                            throw BreakException(PackageInfo(repo, version, pkgArch))
                        }
                    }
                }
            } catch (e: BreakException) {
                return e.result
            }
        }
        return null
    }

    private class BreakException(val result: PackageInfo) : Exception()
}