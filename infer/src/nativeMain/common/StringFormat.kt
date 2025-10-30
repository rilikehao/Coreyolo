package common

import kotlin.math.pow
import kotlin.math.round

object StringFormat {
    fun Double.toString(decimalPlaces: Int): String {
        if (decimalPlaces < 0) return this.toString()

        val factor = 10.0.pow(decimalPlaces.toDouble()).toLong()
        val rounded = round(this * factor) / factor

        val integerPart = rounded.toLong()
        val decimalPart = round((rounded - integerPart) * factor).toLong()

        return if (decimalPlaces == 0) {
            integerPart.toString()
        } else {
            "$integerPart.${decimalPart.toString().padStart(decimalPlaces, '0')}"
        }
    }
}
