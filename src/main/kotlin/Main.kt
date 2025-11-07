fun main(args: Array<String>) {
    SystemUtils.checkHostTools()
    val commands = if (args.isEmpty()) listOf("download", "rga", "mpp", "rknpu2", "acl", "ffmpeg", "mnn", "zLMediaKit", "native") else args.toList()

    commands.forEach { command ->
        when (command) {
            "download" -> PackageDownloader().downloadAll()
            "rga" -> ProjectBuilder.buildRGA()
            "mpp" -> ProjectBuilder.buildMPP()
            "rknpu2" -> ProjectBuilder.buildRKNPU2()
            "acl" -> ProjectBuilder.buildACL()
            "ffmpeg" -> ProjectBuilder.buildFFmpeg()
            "mnn" -> ProjectBuilder.buildMNN()
            "native" -> ProjectBuilder.buildNative()
            "zLMediaKit" -> ProjectBuilder.buildZLMediaKit()
            "clean" -> ProjectBuilder.clean()
            "image" -> ProjectBuilder.buildAppImage()
            "image-debug" -> ProjectBuilder.buildAppImage()
            "train" -> TrainEnvBuilder.buildTrainEnv()
            "train-cuda" -> TrainEnvBuilder.buildTrainEnv("cuda")
            "train-export" -> TrainEnvBuilder.exportModels()
            "train-clean" -> TrainEnvBuilder.clean()
            else -> throw Error("未识别的参数")
        }
    }
}
