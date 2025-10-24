plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

kotlin {
    linuxX64("x64")
    linuxArm64("rk3588")

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
        val targetName = name
        binaries {
            executable("YoloInfer-$targetName")
        }
        compilations["main"].apply {
            defaultSourceSet {
                kotlin.srcDir("src/nativeMain/kotlin")
                kotlin.srcDir("src/nativeMain/$targetName")
                dependencies {
                    implementation("co.touchlab:kermit:2.0.8")
                    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                }
            }
            cinterops {
                listOf("native", "videodev2", "lua", "ffmpeg").forEach { cinteropName ->
                    create(cinteropName) {
                        defFile(project.file("src/nativeMain/def/$targetName/$cinteropName.def"))
                    }
                }
            }
        }
    }
}

tasks.register("install") {
    dependsOn("x64Binaries", "rk3588Binaries")

    val buildType = project.findProperty("buildType") as String? ?: "Release"

    doLast {
        val platforms = listOf("x64", "rk3588")
        platforms.forEach { platform ->
            val executableFile =
                file("build/bin/$platform/YoloInfer-${platform}${buildType}Executable/YoloInfer-$platform.kexe")
            val installDir = file("../$platform/root/usr/local/bin")
            val targetFile = file("$installDir/YoloInfer")

            if (!executableFile.exists()) {
                throw GradleException("Executable not found: ${executableFile.absolutePath}")
            }

            installDir.mkdirs()
            executableFile.copyTo(targetFile, overwrite = true)
            targetFile.setExecutable(true)

            println("Installed ${executableFile.name} (${buildType.lowercase()}) to ${targetFile.absolutePath}")
        }
    }
}

tasks.withType<Wrapper> {
    gradleVersion = "9.1.0"
    distributionType = Wrapper.DistributionType.BIN
}
