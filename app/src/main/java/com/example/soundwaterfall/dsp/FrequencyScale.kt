package com.example.soundwaterfall.dsp

/**
 * The single source of the frequency axis, shared by the spectrum, the waterfall
 * and the axis strip so the three cannot drift out of alignment (spec §4).
 *
 * Everything derives from the GRANTED sample rate. Nothing here may assume
 * 48 kHz: if the device refuses it, capture falls back to 44.1 kHz and every
 * label has to follow (spec §5.1).
 */
class FrequencyScale(val sampleRate: Int, val binCount: Int) {

    init {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        require(binCount >= 2) { "binCount must be at least 2, was $binCount" }
    }

    /** The transform length that produced [binCount] bins. */
    val fftSize: Int = (binCount - 1) * 2

    val nyquist: Float = sampleRate / 2f

    val binWidth: Float = sampleRate.toFloat() / fftSize

    fun frequencyOf(bin: Int): Float = bin * binWidth

    /** Linear mapping of a frequency onto a pixel column. */
    fun xOf(frequency: Float, widthPx: Int): Float =
        if (widthPx <= 0) 0f else frequency / nyquist * widthPx

    /** Gridline frequencies from DC up to and including Nyquist where it lands on a step. */
    fun gridlineFrequencies(stepHz: Int): List<Int> {
        require(stepHz > 0) { "stepHz must be positive, was $stepHz" }
        val out = ArrayList<Int>(8)
        var f = 0
        while (f <= nyquist) {
            out += f
            f += stepHz
        }
        return out
    }
}
