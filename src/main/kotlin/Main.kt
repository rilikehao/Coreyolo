fun main(args: Array<String>) {
    SystemUtils.checkHostTools()
    val commands = if (args.isEmpty()) listOf("download", "native") else args.toList()

    commands.forEach { command ->
        when (command) {
            "download" -> PackageDownloader().downloadAll()
            "native" -> ProjectBuilder.buildNative()
            "rga" -> ProjectBuilder.buildRGA()
            "mpp" -> ProjectBuilder.buildMPP()
            "rknpu2" -> ProjectBuilder.buildRKNPU2()
            "ffmpeg" -> ProjectBuilder.buildFFmpeg()
            "mnn" -> ProjectBuilder.buildMNN()
            "clean" -> ProjectBuilder.clean()
            "image" -> ProjectBuilder.buildAppImage()
            else -> throw Error("未识别的参数")
        }
    }
}
