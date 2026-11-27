package com.example.commonsneedsphoto.data.photo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** A straight edge found in the analysis image, in analysis pixels. */
data class LineSegment(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val length: Double,
) {
    val midX: Double get() = (x1 + x2) / 2.0
    val midY: Double get() = (y1 + y2) / 2.0

    /** Radians, 0 = vertical, positive = leans to the right going down. */
    fun angleFromVertical(): Double {
        var dx = x2 - x1
        var dy = y2 - y1
        if (dy < 0) {
            dx = -dx
            dy = -dy
        }
        return atan2(dx, dy)
    }

    /** Radians, 0 = horizontal, positive = goes down to the right. */
    fun angleFromHorizontal(): Double {
        var dx = x2 - x1
        var dy = y2 - y1
        if (dx < 0) {
            dx = -dx
            dy = -dy
        }
        return atan2(dy, dx)
    }
}

/**
 * Simplified LSD: gradient-orientation region growing on a small grey copy,
 * then a principal-axis fit per region. Pure Kotlin, JVM-testable.
 */
object LineSegmentDetector {

    const val ANALYSIS_MAX_SIDE = 1024
    private const val GRADIENT_THRESHOLD = 5f
    private val COS_TOLERANCE = cos(Math.toRadians(22.5)).toFloat()
    private const val MIN_LENGTH_FRACTION = 0.035
    private const val MIN_ASPECT = 6.0
    private const val MIN_DENSITY = 0.7
    private const val ORDER_BINS = 1024

    fun greyscale(argb: IntArray, width: Int, height: Int): FloatArray =
        FloatArray(width * height) { index ->
            val pixel = argb[index]
            (((pixel ushr 16) and 0xFF) * 77 + ((pixel ushr 8) and 0xFF) * 150 + (pixel and 0xFF) * 29) / 256f
        }

    fun detect(grey: FloatArray, width: Int, height: Int): List<LineSegment> {
        if (width < 16 || height < 16 || grey.size < width * height) return emptyList()
        val g = binomialBlur(grey, width, height)
        val n = width * height
        val magnitude = FloatArray(n)
        val dirX = FloatArray(n)
        val dirY = FloatArray(n)
        var maxMagnitude = 0f
        for (y in 1 until height - 1) {
            var i = y * width + 1
            for (x in 1 until width - 1) {
                val tl = g[i - width - 1]
                val t = g[i - width]
                val tr = g[i - width + 1]
                val l = g[i - 1]
                val r = g[i + 1]
                val bl = g[i + width - 1]
                val b = g[i + width]
                val br = g[i + width + 1]
                val gx = ((tr + 2f * r + br) - (tl + 2f * l + bl)) / 8f
                val gy = ((bl + 2f * b + br) - (tl + 2f * t + tr)) / 8f
                val m = sqrt(gx * gx + gy * gy)
                magnitude[i] = m
                if (m > 0f) {
                    dirX[i] = gx / m
                    dirY[i] = gy / m
                }
                if (m > maxMagnitude) maxMagnitude = m
                i++
            }
        }
        if (maxMagnitude < GRADIENT_THRESHOLD) return emptyList()
        // Border pixels keep magnitude 0, so they start as "used" and region
        // growing never steps outside the image.
        val used = BooleanArray(n) { magnitude[it] < GRADIENT_THRESHOLD }
        val order = orderByMagnitude(magnitude, used, maxMagnitude)

        val minLength = MIN_LENGTH_FRACTION * max(width, height)
        val minPixels = (minLength * 0.7).toInt()
        val neighbours = intArrayOf(-width - 1, -width, -width + 1, -1, 1, width - 1, width, width + 1)
        val region = IntArray(n)
        val segments = ArrayList<LineSegment>()
        for (seed in order) {
            if (used[seed]) continue
            used[seed] = true
            region[0] = seed
            var size = 1
            var sumX = dirX[seed]
            var sumY = dirY[seed]
            var refX = sumX
            var refY = sumY
            var head = 0
            while (head < size) {
                val p = region[head++]
                for (d in neighbours) {
                    val q = p + d
                    if (used[q]) continue
                    if (dirX[q] * refX + dirY[q] * refY < COS_TOLERANCE) continue
                    used[q] = true
                    region[size++] = q
                    sumX += dirX[q]
                    sumY += dirY[q]
                    val norm = sqrt(sumX * sumX + sumY * sumY)
                    refX = sumX / norm
                    refY = sumY / norm
                }
            }
            if (size < minPixels) continue
            fitSegment(region, size, width, magnitude, minLength)?.let(segments::add)
        }
        return segments
    }

    private fun fitSegment(
        region: IntArray,
        size: Int,
        width: Int,
        magnitude: FloatArray,
        minLength: Double,
    ): LineSegment? {
        var sw = 0.0
        var sx = 0.0
        var sy = 0.0
        for (k in 0 until size) {
            val i = region[k]
            val w = magnitude[i].toDouble()
            sw += w
            sx += w * (i % width)
            sy += w * (i / width)
        }
        val mx = sx / sw
        val my = sy / sw
        var cxx = 0.0
        var cyy = 0.0
        var cxy = 0.0
        for (k in 0 until size) {
            val i = region[k]
            val w = magnitude[i].toDouble()
            val dx = i % width - mx
            val dy = i / width - my
            cxx += w * dx * dx
            cyy += w * dy * dy
            cxy += w * dx * dy
        }
        val theta = 0.5 * atan2(2 * cxy / sw, (cxx - cyy) / sw)
        val ux = cos(theta)
        val uy = sin(theta)
        var tMin = Double.MAX_VALUE
        var tMax = -Double.MAX_VALUE
        var sMin = Double.MAX_VALUE
        var sMax = -Double.MAX_VALUE
        for (k in 0 until size) {
            val i = region[k]
            val dx = i % width - mx
            val dy = i / width - my
            val t = dx * ux + dy * uy
            val s = -dx * uy + dy * ux
            if (t < tMin) tMin = t
            if (t > tMax) tMax = t
            if (s < sMin) sMin = s
            if (s > sMax) sMax = s
        }
        val length = tMax - tMin
        val thickness = sMax - sMin + 1.0
        if (length < minLength || length / thickness < MIN_ASPECT || size < MIN_DENSITY * length) return null
        return LineSegment(mx + ux * tMin, my + uy * tMin, mx + ux * tMax, my + uy * tMax, length)
    }

    /** Strongest gradients first (LSD's pseudo-ordering, 1024 bins). */
    private fun orderByMagnitude(magnitude: FloatArray, used: BooleanArray, maxMagnitude: Float): IntArray {
        val scale = (ORDER_BINS - 1) / maxMagnitude
        val counts = IntArray(ORDER_BINS)
        var total = 0
        for (i in magnitude.indices) {
            if (!used[i]) {
                counts[(magnitude[i] * scale).toInt()]++
                total++
            }
        }
        val start = IntArray(ORDER_BINS)
        var acc = 0
        for (bin in ORDER_BINS - 1 downTo 0) {
            start[bin] = acc
            acc += counts[bin]
        }
        val order = IntArray(total)
        for (i in magnitude.indices) {
            if (!used[i]) {
                val bin = (magnitude[i] * scale).toInt()
                order[start[bin]++] = i
            }
        }
        return order
    }

    private fun binomialBlur(src: FloatArray, width: Int, height: Int): FloatArray {
        val kernel = floatArrayOf(1f, 4f, 6f, 4f, 1f)
        val tmp = FloatArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var s = 0f
                for (j in -2..2) s += kernel[j + 2] * src[row + (x + j).coerceIn(0, width - 1)]
                tmp[row + x] = s / 16f
            }
        }
        val out = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var s = 0f
                for (j in -2..2) s += kernel[j + 2] * tmp[(y + j).coerceIn(0, height - 1) * width + x]
                out[y * width + x] = s / 16f
            }
        }
        return out
    }
}
