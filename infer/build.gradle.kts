plugins {
    kotlin("multiplatform") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
}

repositories {
    mavenCentral()
}

kotlin {
    linuxX64("x86_64") {
        compilations["main"].apply {
            cinterops {
                listOf("ffmpeg").forEach { cinteropName ->
                    create(cinteropName) {
                        defFile(project.file("src/def/x86_64/$cinteropName.def"))
                    }
                }
            }
        }
    }

    linuxArm64("aarch64-rockchip") {
        compilations["main"].apply {
            cinterops {
                listOf("ffmpeg-rockchip").forEach { cinteropName ->
                    create(cinteropName) {
                        defFile(project.file("src/def/aarch64/$cinteropName.def"))
                    }
                }
            }
        }
    }

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
        val targetName = name
        val cpuName = name.takeWhile { it != '-' }
        binaries {
            executable("YoloInfer-$targetName")
        }
        compilations["main"].apply {
            defaultSourceSet {
                kotlin.srcDir("src/nativeMain/$cpuName")
                dependencies {
                    implementation("co.touchlab:kermit:2.0.8")
                    implementation("com.akuleshov7:ktoml-core:0.7.1")
                    implementation("com.akuleshov7:ktoml-file:0.7.1")
                    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
                    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                }
            }
            cinterops {
                listOf("native", "videodev2", "lua").forEach { cinteropName ->
                    create(cinteropName) {
                        defFile(project.file("src/def/$cpuName/$cinteropName.def"))
                    }
                }
            }
        }
    }
}

tasks.register("install") {
    dependsOn("x86_64Binaries", "aarch64-rockchipBinaries")

    val buildType = project.findProperty("buildType") as String

    doLast {
        listOf("x86_64", "aarch64-rockchip").forEach { platform ->
            val cpuName = platform.takeWhile { it != '-' }
            val executableFile =
                file("build/bin/$platform/YoloInfer-${platform}${buildType}Executable/YoloInfer-$platform.kexe")
            val installDir = file("../$cpuName/root/usr/local/bin")
            val targetFile = file("$installDir/YoloInfer-$platform")

            if (!executableFile.exists()) {
                throw GradleException("Executable not found: ${executableFile.absolutePath}")
            }

            installDir.mkdirs()
            executableFile.copyTo(targetFile, overwrite = true)
            targetFile.setExecutable(true)

            println("Installed ${executableFile.name} ($buildType) to ${targetFile.absolutePath}")
        }
    }
}

tasks.withType<Wrapper> {
    gradleVersion = "9.1.0"
    distributionType = Wrapper.DistributionType.BIN
}
