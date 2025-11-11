package common

import cnames.structs.Image
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
data class Frame(val timestamp: Instant, val original: CPointer<Image>, val processed: CPointer<Image>?)
