package com.example.soundwaterfall.dsp

/**
 * Maps dB bins onto pixel columns. Both charts use this, so their columns line
 * up exactly (spec §6.3).
 *
 * Two regimes, and on the 720 px test device BOTH occur depending on FFT size:
 *
 *  - bins >= columns (N=2048 gives 1025 bins, N=4096 gives 2049): take the
 *    MAXIMUM over each column's bin span. The mean would bury narrow peaks,
 *    which is exactly what this app exists to show.
 *  - bins < columns (N=1024 gives only 513 bins): linearly interpolate between
 *    neighbouring bins. This is the default case at the smallest FFT size, not
 *    an edge case.
 *
 * Allocates nothing. Writes [out].size values.
 */
object BinReducer {

    fun reduce(db: FloatArray, out: FloatArray) {
        val bins = db.size
        val cols = out.size
        if (bins == 0 || cols == 0) return

        if (bins >= cols) {
            for (c in 0 until cols) {
                val lo = (c.toLong() * bins / cols).toInt()
                val hiRaw = ((c + 1).toLong() * bins / cols).toInt()
                val hi = if (hiRaw > lo) minOf(hiRaw, bins) else minOf(lo + 1, bins)
                var m = db[lo]
                for (b in lo + 1 until hi) {
                    if (db[b] > m) m = db[b]
                }
                out[c] = m
            }
        } else {
            val denom = (cols - 1).coerceAtLeast(1)
            val span = (bins - 1).toFloat()
            for (c in 0 until cols) {
                val pos = c * span / denom
                val lo = pos.toInt().coerceIn(0, bins - 1)
                val hi = (lo + 1).coerceAtMost(bins - 1)
                out[c] = db[lo] + (db[hi] - db[lo]) * (pos - lo)
            }
        }
    }
}
