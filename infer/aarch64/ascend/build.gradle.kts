plugins {
    kotlin("multiplatform") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
}

repositories {
    mavenCentral()
}

kotlin {
    linuxArm64("aarch64-ascend") {
        val targetName = name
        val cpuName = name.takeWhile { it != '-' }
        binaries {
            executable("YoloInfer-$targetName")
        }
        compilations["main"].apply {
            defaultSourceSet {
                kotlin.srcDir("kotlin")
                dependencies {
                    implementation("co.touchlab:kermit:2.0.8")
                    implementation("com.akuleshov7:ktoml-core:0.7.1")
                    implementation("com.akuleshov7:ktoml-file:0.7.1")
                    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
                    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                }
            }
            cinterops {
                listOf("videodev2", "lua").forEach { cinteropName ->
                    create(cinteropName) {
                        defFile(project.file("def/$cpuName/$cinteropName.def"))
                    }
                }
                listOf("ffmpeg", "native", "acl").forEach { cinteropName ->
                    create(cinteropName) {
                        defFile(project.file("def/$cinteropName.def"))
                    }
                }
            }
        }
    }
}

tasks.register("install") {
    dependsOn("aarch64-ascendBinaries")

    val buildType = project.findProperty("buildType") as String

    doLast {
        val targetName = "aarch64-ascend"
        val cpuName = targetName.takeWhile { it != '-' }
        val executableFile =
            file("build/bin/$targetName/YoloInfer-$targetName${buildType}Executable/YoloInfer-$targetName.kexe")
        val installDir = file("../../../$cpuName/root/usr/local/bin")
        val targetFile = file("$installDir/YoloInfer-$targetName")

        if (!executableFile.exists()) {
            throw GradleException("Executable not found: ${executableFile.absolutePath}")
        }

        installDir.mkdirs()
        executableFile.copyTo(targetFile, overwrite = true)
        targetFile.setExecutable(true)

        println("Installed ${executableFile.name} ($buildType) to ${targetFile.absolutePath}")
    }
}

tasks.withType<Wrapper> {
    gradleVersion = "9.1.0"
    distributionType = Wrapper.DistributionType.BIN
}
