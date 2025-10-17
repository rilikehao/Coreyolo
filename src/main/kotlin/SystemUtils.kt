object SystemUtils {
    fun checkHostTools() {
        val requiredTools = listOf("git", "meson", "cmake", "make", "pkg-config", "aarch64-linux-gnu-g++")
        val missingTools = requiredTools.filter { tool ->
            Runtime.getRuntime().exec(arrayOf("which", tool)).waitFor() != 0
        }
        if (missingTools.isNotEmpty()) {
            throw RuntimeException("Missing required tools: ${missingTools.joinToString(", ")}")
        }
    }

    fun ProcessBuilder.runCommand() {
        val process = this.apply { redirectErrorStream(true) }.start()
        process.inputStream.bufferedReader().forEachLine { println(it) }
        if (process.waitFor() != 0) {
            throw RuntimeException("Command failed: ${this.command().joinToString(" ")}")
        }
    }
}
