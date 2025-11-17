package common

import com.akuleshov7.ktoml.file.TomlFileReader
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

object AppConfig {
    lateinit var instance: Configuration
    
    @Serializable
    enum class SourceType {
        IMAGE, VIDEO, CAMERA,
    }

    @Serializable
    data class Configuration(
        val source: SourceConfig,
        val paths: PathsConfig,
        val processing: ProcessingConfig,
        val streams: List<StreamConfig> = emptyList(),
    )

    @Serializable
    data class SourceConfig(
        val type: SourceType,
    )

    @Serializable
    data class PathsConfig(
        val model: String,
        val description: String,
        val drawScript: String = "",
    )

    @Serializable
    data class StreamConfig(
        val id: String,
        val source: String,
    )

    @Serializable
    data class ProcessingConfig(
        val npuThreads: Int,
        val cpuThreads: Int,
        val bitRateView: Long = 1500000,
        val bitRateStorage: Long = 750000,
        val fpsYolo: Double = 90000.0,
        val fpsDecode: Double = 90000.0,
        val mute: Boolean = false,
    )

    fun loadFromFile(configPath: String) {
        instance = TomlFileReader.decodeFromFile(serializer<Configuration>(), configPath)
    }
}
