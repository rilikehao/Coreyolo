plugins {
    application
    kotlin("jvm") version "2.2.20"
    kotlin("multiplatform") version "2.2.20" apply false
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.10")
    implementation("com.github.luben:zstd-jni:1.5.7-5")
}

application {
    mainClass.set("MainKt")
}
