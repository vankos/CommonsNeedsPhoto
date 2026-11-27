package com.example.commonsneedsphoto.data.photo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A small, deterministic "auto light / colour / contrast" correction.
 *
 * The whole correction is a linear [ColorMatrix], so it is cheap enough to
 * apply to a live Compose preview and to a 4096 px bitmap through Canvas
 * without touching pixels one by one. Analysis is done on a small sampled
 * copy: percentiles set the black/white points, channel means remove a colour
 * cast, the mean luma lifts or lowers the exposure, the luma spread picks a
 * contrast factor and the mean saturation decides whether a saturation boost
 * is needed. [strength] blends the result with the untouched image.
 */
object AutoEnhance {

    private const val SAMPLE_MAX_SIDE = 256
    private const val LUMA_R = 0.299f
    private const val LUMA_G = 0.587f
    private const val LUMA_B = 0.114f

    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    fun lerpWithIdentity(matrix: FloatArray, strength: Float): FloatArray {
        val s = strength.coerceIn(0f, 1f)
        val identity = identity()
        return FloatArray(20) { index ->
            identity[index] + (matrix[index] - identity[index]) * s
        }
    }

    /**
     * Builds a full-strength correction matrix from ARGB pixels.
     * Pure Kotlin so it can be unit-tested without Android graphics.
     */
    fun analyze(pixels: IntArray): FloatArray {
        if (pixels.isEmpty()) return identity()

        val histogram = IntArray(256)
        var redSum = 0L
        var greenSum = 0L
        var blueSum = 0L
        var saturationSum = 0.0
        var count = 0

        for (pixel in pixels) {
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha == 0) continue
            val red = (pixel ushr 16) and 0xFF
            val green = (pixel ushr 8) and 0xFF
            val blue = pixel and 0xFF
            val luma = ((red * 77 + green * 150 + blue * 29) shr 8).coerceIn(0, 255)
            histogram[luma]++
            redSum += red
            greenSum += green
            blueSum += blue
            val maxChannel = max(red, max(green, blue))
            val minChannel = minOf(red, green, blue)
            saturationSum += if (maxChannel == 0) 0.0 else (maxChannel - minChannel).toDouble() / maxChannel
            count++
        }
        if (count == 0) return identity()

        var black = percentile(histogram, count, 0.005)
        var white = percentile(histogram, count, 0.995)
        if (white - black < 24) {
            black = 0
            white = 255
        }
        var gain = 255f / (white - black)
        var offset = -black * gain

        // Grey-world white balance, deliberately gentle: sunsets and night
        // shots should keep their colour mood.
        val meanRed = redSum.toDouble() / count
        val meanGreen = greenSum.toDouble() / count
        val meanBlue = blueSum.toDouble() / count
        val meanChannel = (meanRed + meanGreen + meanBlue) / 3.0
        val whiteBalanceRed = whiteBalance(meanRed, meanChannel)
        val whiteBalanceGreen = whiteBalance(meanGreen, meanChannel)
        val whiteBalanceBlue = whiteBalance(meanBlue, meanChannel)

        // Mean luma after levels: nudges underexposed photos towards mid-grey.
        var mappedSum = 0.0
        var mappedSquareSum = 0.0
        for (value in 0..255) {
            val mapped = (value * gain + offset).coerceIn(0f, 255f)
            val weight = histogram[value]
            mappedSum += mapped * weight
            mappedSquareSum += mapped * mapped * weight
        }
        val meanMapped = mappedSum / count
        val exposure = ((0.5 - meanMapped / 255.0) * 0.45 * 255.0).coerceIn(-28.0, 28.0)

        // Contrast from the luma spread; a flat photo gets a little more.
        val variance = (mappedSquareSum / count - meanMapped * meanMapped).coerceAtLeast(0.0)
        val deviation = sqrt(variance) / 255.0
        val rawContrast = if (deviation < 0.02) 1.12 else (0.22 / deviation).coerceIn(0.95, 1.30)
        val contrast = 1.0 + (rawContrast - 1.0) * 0.55

        val meanSaturation = saturationSum / count
        val saturation = (1.0 + (0.32 - meanSaturation).coerceIn(0.0, 0.18) * 0.7)
            .coerceIn(1.0, 1.16)

        // out = saturation(contrast(levelsAndWhiteBalance(in)))
        val redGain = gain * whiteBalanceRed * contrast.toFloat()
        val greenGain = gain * whiteBalanceGreen * contrast.toFloat()
        val blueGain = gain * whiteBalanceBlue * contrast.toFloat()
        val contrastOffset = 128f * (1f - contrast.toFloat())
        val redOffset = (offset * whiteBalanceRed + exposure).toFloat() * contrast.toFloat() + contrastOffset
        val greenOffset = (offset * whiteBalanceGreen + exposure).toFloat() * contrast.toFloat() + contrastOffset
        val blueOffset = (offset * whiteBalanceBlue + exposure).toFloat() * contrast.toFloat() + contrastOffset

        val s = saturation.toFloat()
        val sr = s + (1f - s) * LUMA_R
        val sg = (1f - s) * LUMA_G
        val sb = (1f - s) * LUMA_B
        val gr = (1f - s) * LUMA_R
        val gg = s + (1f - s) * LUMA_G
        val gb = (1f - s) * LUMA_B
        val br = (1f - s) * LUMA_R
        val bg = (1f - s) * LUMA_G
        val bb = s + (1f - s) * LUMA_B

        return floatArrayOf(
            sr * redGain, sg * greenGain, sb * blueGain, 0f,
            sr * redOffset + sg * greenOffset + sb * blueOffset,

            gr * redGain, gg * greenGain, gb * blueGain, 0f,
            gr * redOffset + gg * greenOffset + gb * blueOffset,

            br * redGain, bg * greenGain, bb * blueGain, 0f,
            br * redOffset + bg * greenOffset + bb * blueOffset,

            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Analyses a bitmap (downscaled first) and returns [strength] correction. */
    fun matrixFor(bitmap: Bitmap, strength: Float): FloatArray {
        if (strength <= 0f || bitmap.width <= 0 || bitmap.height <= 0) return identity()
        val scale = SAMPLE_MAX_SIDE.toFloat() / max(bitmap.width, bitmap.height)
        val sampleWidth = (bitmap.width * scale).roundToInt().coerceIn(1, SAMPLE_MAX_SIDE)
        val sampleHeight = (bitmap.height * scale).roundToInt().coerceIn(1, SAMPLE_MAX_SIDE)
        val sample = if (sampleWidth == bitmap.width && sampleHeight == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, true)
        }
        val pixels = IntArray(sampleWidth * sampleHeight)
        sample.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
        if (sample !== bitmap) sample.recycle()
        return lerpWithIdentity(analyze(pixels), strength)
    }

    /**
     * Applies the correction to a copy of [bitmap]. Returns [bitmap] itself
     * when the correction is off.
     */
    fun apply(bitmap: Bitmap, strength: Float): Bitmap {
        if (strength <= 0f) return bitmap
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(matrixFor(bitmap, strength)))
        }
        Canvas(out).drawBitmap(bitmap, 0f, 0f, paint)
        return out
    }

    private fun whiteBalance(mean: Double, target: Double): Float {
        if (mean < 12.0) return 1f
        val raw = (target / mean).toFloat().coerceIn(0.90f, 1.10f)
        return 1f + (raw - 1f) * 0.6f
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Double): Int {
        val target = (total * fraction).roundToInt().coerceIn(0, total)
        var accumulated = 0
        for (value in 0..255) {
            accumulated += histogram[value]
            if (accumulated >= target) return value
        }
        return 255
    }

    /** Only used to make the pure analysis testable without Android graphics. */
    fun applyToArgb(matrix: FloatArray, red: Int, green: Int, blue: Int): Triple<Int, Int, Int> {
        fun channel(row: Int): Int = (
            matrix[row * 5] * red +
                matrix[row * 5 + 1] * green +
                matrix[row * 5 + 2] * blue +
                matrix[row * 5 + 4]
            ).roundToInt().coerceIn(0, 255)
        return Triple(channel(0), channel(1), channel(2))
    }
}
