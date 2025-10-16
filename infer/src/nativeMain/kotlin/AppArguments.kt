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
    val pathDrawScript by parser.option(ArgType.String, "draw-script").default("")
}
