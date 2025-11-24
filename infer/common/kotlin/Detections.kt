package common

import cnames.structs.Infer
import cnames.structs.InferTask
import common.StringFormat.toString
import common.Utils.check
import common.Utils.roundToEpochMs
import common.Utils.toTimeString
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.getOrElse
import kotlinx.coroutines.flow.*
import platform.native.*
import platform.posix.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
object Detections {
    fun dump(timestamp: Instant, task: CPointer<InferTask>): String {
        return Array(SizeDetections(task)) { i ->
            PtrDetections(task)!![i].let {
                val score = it.score_.toDouble().toString(6)
                val name = it.name_!!.toKString()
                val x0 = it.bound_.x0_
                val x1 = it.bound_.x1_
                val y0 = it.bound_.y0_
                val y1 = it.bound_.y1_
                "$score,$name,$x0,$x1,$y0,$y1"
            }
        }.joinToString(";").let { "${timestamp.toTimeString()};$it" }
    }

    fun load(infer: CPointer<Infer>, s: String): CPointer<InferTask> {
        val task = CreateInferTask()!!
        if (s.isNotEmpty()) s.split(';').forEach { si ->
            val split = si.split(',')
            cValue<Detection> {
                score_ = split[0].toFloat()
                name_ = NormalizeName(infer, split[1])
                bound_.x0_ = split[2].toInt()
                bound_.x1_ = split[3].toInt()
                bound_.y0_ = split[4].toInt()
                bound_.y1_ = split[5].toInt()
            }.let { detection -> AddDetection(task, detection) }
        }
        return task
    }

    suspend fun dumpStrings(input: Flow<String>, outputPath: String) {
        val file = fopen(outputPath, "w")
        try {
            input.collect {
                fputs(it, file)
                fputc('\n'.code, file)
                fflush(file)
            }
        } finally {
            fclose(file)
        }
    }

    fun loadAll(infer: CPointer<Infer>, inputPath: String) =
        mutableListOf<String>().also {
            val dir = opendir(inputPath) ?: return@also
            try {
                while (true) {
                    val entry = readdir(dir) ?: break
                    val name = entry.pointed.d_name.toKString()
                    if (name == "." || name == ".." || !name.endsWith(".txt")) continue
                    it.add(name)
                }
            } finally {
                closedir(dir)
            }
        }.also { it.sort() }.asFlow().transform { name ->
            val file = fopen("$inputPath/$name", "r").check("fopen")
            fun readUntil(stop: Char): String {
                val s = StringBuilder()
                while (true) {
                    val ch = fgetc(file)
                    if (ch == -1 || ch == stop.code) break
                    s.append(ch.toChar())
                }
                return s.toString()
            }
            while (true) {
                val timestamp = readUntil(';')
                if (timestamp.isEmpty()) break
                val detections = readUntil('\n')
                emit(Pair(EpochMsFromTimeString(timestamp), { load(infer, detections) }))
            }
        }

    fun CoroutineScope.mux(
        images: Flow<Command.CommandImage>,
        labels: Flow<Pair<Long, () -> CPointer<InferTask>>>
    ): Flow<Command> {
        val ch = labels.produceIn(this)
        var timestamp = 0L
        var loadTask: () -> CPointer<InferTask> = { throw Error("") }
        return images.map { commandImage ->
            println("image: ${commandImage.timestamp}")
            val epochMs = commandImage.timestamp.roundToEpochMs()
            while (timestamp < epochMs) {
                ch.receiveCatching().getOrElse {
                    Pair(Long.MAX_VALUE, { throw Error("") })
                }.let { (t, l) -> timestamp = t; loadTask = l }
                println("label: ${Instant.fromEpochMilliseconds(timestamp)}")
            }
            if (timestamp == epochMs) {
                val task = loadTask()
                SetImage(task, commandImage.data)
                return@map Command.CommandLabel(commandImage.timestamp, task)
            }
            println("raw")
            return@map commandImage
        }.onCompletion {
            ch.consumeEach {}
        }
    }
}
