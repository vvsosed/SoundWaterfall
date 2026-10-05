package com.example.soundwaterfall.dsp

import kotlin.math.roundToInt

/** Compact frequency labels for the shared axis strip. */
object AxisLabels {

    fun format(hz: Int): String {
        if (hz <= 0) return "0"
        if (hz < 1000) return hz.toString()
        if (hz % 1000 == 0) return "${hz / 1000}k"
        val tenths = (hz / 100f).roundToInt()
        return "${tenths / 10}.${tenths % 10}k"
    }
}
