import cli.ArgParser
import cli.ArgType
import cli.default
import cli.required

class AppArguments(parser: ArgParser) {
    companion object {
        lateinit var instance: AppArguments
    }

    enum class SourceType {
        IMAGE, VIDEO,
    }

    val sourceType by parser.option(ArgType.Choice<SourceType>(), "source-type").required()
    val pathModel by parser.option(ArgType.String, "model").required()
    val pathDescription by parser.option(ArgType.String, "description").required()
    val pathSource by parser.option(ArgType.String, "source").required()
    val pathTarget by parser.option(ArgType.String, "target").default("")
    val pathDrawScript by parser.option(ArgType.String, "draw-script").default("")
    val fps by parser.option(ArgType.Double, "fps").default(90000.0)
    val encodeFps by parser.option(ArgType.Double, "encode-fps").default(90000.0)
    val mute by parser.option(ArgType.Boolean, "mute").default(false)
}
