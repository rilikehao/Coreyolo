import com.akuleshov7.ktoml.file.TomlFileReader
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

object AppConfig {
    lateinit var instance: Configuration
    
    @Serializable
    enum class SourceType {
        IMAGE, VIDEO,
    }

    @Serializable
    data class Configuration(
        val source: SourceConfig,
        val paths: PathsConfig,
        val processing: ProcessingConfig = ProcessingConfig(),
        val streams: List<StreamConfig> = emptyList(),
    )

    @Serializable
    data class SourceConfig(
        val type: SourceType = SourceType.VIDEO,
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
        val target: String,
    )

    @Serializable
    data class ProcessingConfig(
        val fpsYolo: Double = 90000.0,
        val fpsEncode: Double = 90000.0,
        val mute: Boolean = false,
    )

    fun loadFromFile(configPath: String) {
        instance = TomlFileReader.decodeFromFile(serializer<Configuration>(), configPath)
    }
}
