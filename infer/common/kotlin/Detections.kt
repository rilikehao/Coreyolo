package common

import cnames.structs.Infer
import cnames.structs.InferTask
import common.StringFormat.toString
import common.Utils.timeZone
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.toLocalDateTime
import platform.native.*
import platform.posix.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
object Detections {
    fun dump(timestamp: Instant, task: CPointer<InferTask>): String {
        return Array(SizeDetections(task)) { i ->
            if (i == 0) {
                return@Array ToTimeString(timestamp.toEpochMilliseconds() / 1000.0).useContents { data_.toKString() }
            }
            PtrDetections(task)!![i - 1].let {
                val score = it.score_.toDouble().toString(6)
                val name = it.name_!!.toKString()
                val x0 = it.bound_.x0_
                val x1 = it.bound_.x1_
                val y0 = it.bound_.y0_
                val y1 = it.bound_.y1_
                "$score,$name,$x0,$x1,$y0,$y1"
            }
        }.joinToString(";")
    }

    fun load(infer: CPointer<Infer>, s: String): CPointer<InferTask> {
        val task = CreateInferTask()!!
        s.split(';').forEachIndexed { i, si ->
            if (i == 0) return@forEachIndexed
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

    fun loadAll(infer: CPointer<Infer>, inputPath: String): MutableMap<String, () -> CPointer<InferTask>> {
        val file = fopen(inputPath, "r")
        fun readUntil(stop: Char): String {
            val s = StringBuilder()
            while (true) {
                val ch = fgetc(file)
                if (ch == -1 || ch == stop.code) break
                s.append(ch.toChar())
            }
            return s.toString()
        }

        val m = mutableMapOf<String, () -> CPointer<InferTask>>()
        while (true) {
            val timestamp = readUntil(';')
            if (timestamp.isEmpty()) break
            val detections = readUntil('\n')
            m[timestamp] = { load(infer, detections) }
        }
        return m
    }

    fun MutableMap<String, () -> CPointer<InferTask>>.loadLabels(input: Flow<Command.CommandImage>): Flow<Command> {
        return input.map { commandImage ->
            when (val f = this[commandImage.timestamp.toLocalDateTime(timeZone).toString()]) {
                null -> commandImage
                else -> {
                    val task = f()
                    SetImage(task, commandImage.data)
                    Command.CommandLabel(commandImage.timestamp, task)
                }
            }
        }
    }
}
