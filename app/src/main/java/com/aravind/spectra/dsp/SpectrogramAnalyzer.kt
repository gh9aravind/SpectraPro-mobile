package com.aravind.spectra.dsp

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-math DSP core. No Android dependencies here on purpose, so it is
 * plain-JVM testable (see the accompanying scratch tests) and easy to
 * reason about independently of decode/UI concerns.
 *
 * This mirrors the worker.js implementation used in the web-app version of
 * Spectra, tested there against synthetic sine/noise signals.
 */

private const val FFT_SIZE = 2048
private const val HOP = 1024

data class LevelStats(
    val peakDb: Double,
    val rmsDb: Double,
    val dr: Double,
    val clipCount: Int,
    val peakLinear: Double,
    val rmsLinear: Double
)

data class CutoffResult(
    val cutoffHz: Double,
    val nyquist: Double,
    val isSharp: Boolean,
    val dropPerKHz: Double
)

data class ChannelSpectrogram(
    // flat [col * rows + row] dB matrix, row 0 = lowest frequency
    val dbMatrix: FloatArray,
    val cols: Int,
    val rows: Int
)

data class AnalysisResult(
    val perChannelStats: List<LevelStats>,
    val overallStats: LevelStats,
    val cutoff: CutoffResult,
    val views: Map<String, ChannelSpectrogram>, // "all", "ch1", "ch2"
    val totalSamples: Int,
    val fftSize: Int
)

/** Iterative in-place radix-2 Cooley-Tukey FFT (re/im must be same pow2 length). */
private fun fft(re: DoubleArray, im: DoubleArray) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (bit and j != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j xor bit
        if (i < j) {
            val tr = re[i]; re[i] = re[j]; re[j] = tr
            val ti = im[i]; im[i] = im[j]; im[j] = ti
        }
    }
    var len = 2
    while (len <= n) {
        val half = len shr 1
        val ang = -2 * Math.PI / len
        val wr = cos(ang)
        val wi = sin(ang)
        var i = 0
        while (i < n) {
            var curWr = 1.0
            var curWi = 0.0
            for (k in 0 until half) {
                val a = i + k
                val b = i + k + half
                val vr = re[b] * curWr - im[b] * curWi
                val vi = re[b] * curWi + im[b] * curWr
                val ur = re[a]
                val ui = im[a]
                re[a] = ur + vr; im[a] = ui + vi
                re[b] = ur - vr; im[b] = ui - vi
                val nWr = curWr * wr - curWi * wi
                val nWi = curWr * wi + curWi * wr
                curWr = nWr; curWi = nWi
            }
            i += len
        }
        len = len shl 1
    }
}

private fun hannWindow(size: Int): DoubleArray {
    val w = DoubleArray(size)
    for (i in 0 until size) w[i] = 0.5 - 0.5 * cos(2 * Math.PI * i / (size - 1))
    return w
}
private val WINDOW = hannWindow(FFT_SIZE)

private fun magToDb(mag: Double): Double = 20 * log10(max(mag, 1e-9))

/** STFT -> list of per-frame linear-magnitude arrays (length FFT_SIZE/2). */
private fun computeStft(samples: FloatArray, onProgress: ((Double) -> Unit)? = null): Array<DoubleArray> {
    val half = FFT_SIZE / 2
    val numFrames = max(1, (samples.size - FFT_SIZE) / HOP + 1)
    val frames = Array(numFrames) { DoubleArray(half) }
    val re = DoubleArray(FFT_SIZE)
    val im = DoubleArray(FFT_SIZE)
    for (f in 0 until numFrames) {
        val start = f * HOP
        for (i in 0 until FFT_SIZE) {
            val idx = start + i
            re[i] = (if (idx < samples.size) samples[idx].toDouble() else 0.0) * WINDOW[i]
            im[i] = 0.0
        }
        fft(re, im)
        val mags = frames[f]
        for (k in 0 until half) mags[k] = sqrt(re[k] * re[k] + im[k] * im[k]) / FFT_SIZE
        if (onProgress != null && (f and 127) == 0) onProgress(f.toDouble() / numFrames)
    }
    return frames
}

private fun combineChannelsPower(frameArrays: List<Array<DoubleArray>>): Array<DoubleArray> {
    val numFrames = frameArrays[0].size
    val half = frameArrays[0][0].size
    val out = Array(numFrames) { DoubleArray(half) }
    for (f in 0 until numFrames) {
        for (k in 0 until half) {
            var sumSq = 0.0
            for (c in frameArrays.indices) {
                val v = frameArrays[c][f][k]
                sumSq += v * v
            }
            out[f][k] = sqrt(sumSq / frameArrays.size)
        }
    }
    return out
}

private fun downsampleForDisplay(frames: Array<DoubleArray>, cols: Int, rows: Int): FloatArray {
    val numFrames = frames.size
    val half = frames[0].size
    val colStep = numFrames.toDouble() / cols
    val rowStep = half.toDouble() / rows
    val out = FloatArray(cols * rows)
    for (c in 0 until cols) {
        val fStart = (c * colStep).toInt()
        val fEnd = max(fStart + 1, ((c + 1) * colStep).toInt())
        for (r in 0 until rows) {
            val kStart = (r * rowStep).toInt()
            val kEnd = max(kStart + 1, ((r + 1) * rowStep).toInt())
            var sumSq = 0.0
            var count = 0
            var f = fStart
            while (f < fEnd && f < numFrames) {
                val frame = frames[f]
                var k = kStart
                while (k < kEnd && k < half) { sumSq += frame[k] * frame[k]; count++; k++ }
                f++
            }
            out[c * rows + r] = magToDb(if (count > 0) sqrt(sumSq / count) else 0.0).toFloat()
        }
    }
    return out
}

private fun detectSpectralCutoff(avgLinearSpectrum: DoubleArray, sampleRate: Int): CutoffResult {
    val half = avgLinearSpectrum.size
    val binHz = (sampleRate / 2.0) / half
    val dbs = DoubleArray(half) { magToDb(avgLinearSpectrum[it]) }
    var peakDb = Double.NEGATIVE_INFINITY
    for (v in dbs) if (v > peakDb) peakDb = v

    val topStart = (half * 0.9).toInt()
    val topSorted = dbs.copyOfRange(topStart, half).sorted()
    val noiseFloor = if (topSorted.isNotEmpty()) topSorted[topSorted.size / 2] else -100.0
    val threshold = max(noiseFloor + 8, peakDb - 65)

    var cutoffBin = half - 1
    for (k in half - 1 downTo 1) {
        if (dbs[k] > threshold && dbs[k - 1] > threshold) { cutoffBin = k; break }
    }
    val cutoffHz = cutoffBin * binHz

    val bandBins = max(1, round(1000 / binHz).toInt())
    val beforeIdx = max(0, cutoffBin - round(200 / binHz).toInt())
    val afterIdx = min(half - 1, cutoffBin + bandBins)
    val dropPerKHz = dbs[beforeIdx] - dbs[afterIdx]
    val isSharp = dropPerKHz > 25

    return CutoffResult(cutoffHz, sampleRate / 2.0, isSharp, dropPerKHz)
}

private fun levelStats(samples: FloatArray): LevelStats {
    var peak = 0.0
    var sumSq = 0.0
    var clipCount = 0
    for (s in samples) {
        val a = abs(s.toDouble())
        if (a > peak) peak = a
        sumSq += s.toDouble() * s.toDouble()
        if (a >= 0.999) clipCount++
    }
    val rms = sqrt(sumSq / samples.size)
    val peakDb = 20 * log10(max(peak, 1e-9))
    val rmsDb = 20 * log10(max(rms, 1e-9))
    return LevelStats(peakDb, rmsDb, peakDb - rmsDb, clipCount, peak, rms)
}

/**
 * Runs the full analysis pipeline. Call off the main thread (e.g. from a
 * coroutine on Dispatchers.Default) — this is CPU-bound and can take a
 * few seconds on a multi-minute track.
 */
fun analyze(
    channels: List<FloatArray>,
    sampleRate: Int,
    targetCols: Int,
    targetRows: Int,
    onProgress: ((Double) -> Unit)? = null
): AnalysisResult {
    val numCh = channels.size
    val perChannelStats = channels.map { levelStats(it) }

    val overallStats: LevelStats = run {
        var peakLinear = 0.0
        var sumSqRms = 0.0
        var clipCount = 0
        for (s in perChannelStats) {
            if (s.peakLinear > peakLinear) peakLinear = s.peakLinear
            sumSqRms += s.rmsLinear * s.rmsLinear
            if (s.clipCount > clipCount) clipCount = s.clipCount
        }
        val rmsLinear = sqrt(sumSqRms / numCh)
        val peakDb = 20 * log10(max(peakLinear, 1e-9))
        val rmsDb = 20 * log10(max(rmsLinear, 1e-9))
        LevelStats(peakDb, rmsDb, peakDb - rmsDb, clipCount, peakLinear, rmsLinear)
    }

    val perChannelFrames = channels.mapIndexed { i, ch ->
        computeStft(ch) { p -> onProgress?.invoke((i + p) / numCh) }
    }
    val combinedFrames = if (numCh > 1) combineChannelsPower(perChannelFrames) else perChannelFrames[0]

    val half = FFT_SIZE / 2
    val avgSpectrum = DoubleArray(half)
    for (frame in combinedFrames) for (k in 0 until half) avgSpectrum[k] += frame[k]
    for (k in 0 until half) avgSpectrum[k] = avgSpectrum[k] / combinedFrames.size
    val cutoff = detectSpectralCutoff(avgSpectrum, sampleRate)

    val views = mutableMapOf(
        "all" to ChannelSpectrogram(downsampleForDisplay(combinedFrames, targetCols, targetRows), targetCols, targetRows)
    )
    if (numCh > 1) {
        views["ch1"] = ChannelSpectrogram(downsampleForDisplay(perChannelFrames[0], targetCols, targetRows), targetCols, targetRows)
        views["ch2"] = ChannelSpectrogram(downsampleForDisplay(perChannelFrames[1], targetCols, targetRows), targetCols, targetRows)
    }

    return AnalysisResult(perChannelStats, overallStats, cutoff, views, channels[0].size, FFT_SIZE)
}
