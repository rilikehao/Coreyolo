package common

import cnames.structs.AVDictionary
import kotlinx.cinterop.*
import kotlinx.datetime.FixedOffsetTimeZone
import kotlinx.datetime.UtcOffset
import platform.ffmpeg.AV_ERROR_MAX_STRING_SIZE
import platform.ffmpeg.av_dict_free
import platform.ffmpeg.av_dict_set
import platform.ffmpeg.av_make_error_string
import platform.native.EpochMsFromTimeString
import platform.native.EpochMsToTimeString
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
object Utils {
    val timeZone = FixedOffsetTimeZone(UtcOffset(hours = 8))

    fun Long.toTimeString() = EpochMsToTimeString(this).useContents { data_.toKString() }
    fun Instant.roundToEpochMs() = (this + 0.5.milliseconds).toEpochMilliseconds()
    fun Instant.toTimeString() = roundToEpochMs().toTimeString()
    fun String.toInstant() = Instant.fromEpochMilliseconds(EpochMsFromTimeString(this))

    fun Int.check(api: String) {
        if (this < 0) {
            memScoped {
                val buf = allocArray<ByteVar>(AV_ERROR_MAX_STRING_SIZE)
                av_make_error_string(buf.pointed.ptr, AV_ERROR_MAX_STRING_SIZE.toULong(), this@check)
                throw Error("$api 失败: ${buf.toKString()}")
            }
        }
    }

    fun Int.checkEq0(api: String) {
        if (this != 0) {
            memScoped {
                val buf = allocArray<ByteVar>(AV_ERROR_MAX_STRING_SIZE)
                av_make_error_string(buf.pointed.ptr, AV_ERROR_MAX_STRING_SIZE.toULong(), this@checkEq0)
                throw Error("$api 失败: ${buf.toKString()}")
            }
        }
    }

    fun <T : CPointed> CPointer<T>?.check(api: String): CPointer<T> = this ?: throw Error("$api 失败")

    fun <T : CPointed>  cPointer(block: (CPointer<CPointerVar<T>>) -> Unit) = memScoped {
        val ref = alloc<CPointerVar<T>>()
        block(ref.ptr)
        ref.value!!
    }

    inline fun <reified T : CPointed, R> CPointer<T>.use(destroy: (CPointer<T>) -> Unit, block: (T) -> R) =
        try {
            block(this.pointed)
        } finally {
            destroy(this)
        }


    fun <T> withOptions(vararg m: Pair<String, String>, block: (CPointer<CPointerVar<AVDictionary>>) -> T) = memScoped {
        val options = alloc<CPointerVar<AVDictionary>>()
        m.forEach { (k, v) -> av_dict_set(options.ptr, k, v, 0) }
        try {
            block(options.ptr)
        } finally {
            av_dict_free(options.ptr)
        }
    }
}
