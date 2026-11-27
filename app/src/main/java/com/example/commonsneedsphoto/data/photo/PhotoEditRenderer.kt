package com.example.commonsneedsphoto.data.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Applies the editor's [PhotoEdits] to a processed photo file. The preview in
 * the editor is deliberately smaller; the real work happens here once, on the
 * full-resolution file, and only then is the JPEG re-encoded.
 */
object PhotoEditRenderer {

    const val PREVIEW_MAX_DIMENSION = 1280
    private const val MAX_OUTPUT_DIMENSION = 4096
    private const val JPEG_QUALITY = 92

    fun decodePreview(file: File, maxDimension: Int = PREVIEW_MAX_DIMENSION): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val side = max(decoded.width, decoded.height)
        if (side <= maxDimension) return decoded
        val scale = maxDimension.toFloat() / side
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).roundToInt().coerceAtLeast(1),
            (decoded.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return bitmap
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Projects the four corners of [quad] onto a rectangle. Uses a software
     * canvas because a perspective matrix is not supported by every hardware
     * canvas; drawing into a Bitmap always goes through Skia's software path.
     */
    fun warp(bitmap: Bitmap, quad: PerspectiveQuad?): Bitmap {
        if (quad == null || quad.isFullFrame() || !quad.isUsable()) return bitmap
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return bitmap
        val (rawWidth, rawHeight) = quad.warpedSize(width, height)
        val scale = min(1f, MAX_OUTPUT_DIMENSION.toFloat() / max(rawWidth, rawHeight))
        val outputWidth = (rawWidth * scale).roundToInt().coerceAtLeast(1)
        val outputHeight = (rawHeight * scale).roundToInt().coerceAtLeast(1)
        if (outputWidth < 8 || outputHeight < 8) return bitmap

        val source = floatArrayOf(
            quad.topLeft.x * width, quad.topLeft.y * height,
            quad.topRight.x * width, quad.topRight.y * height,
            quad.bottomRight.x * width, quad.bottomRight.y * height,
            quad.bottomLeft.x * width, quad.bottomLeft.y * height,
        )
        val destination = floatArrayOf(
            0f, 0f,
            outputWidth.toFloat(), 0f,
            outputWidth.toFloat(), outputHeight.toFloat(),
            0f, outputHeight.toFloat(),
        )
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(source, 0, destination, 0, 4)) return bitmap

        val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawColor(Color.BLACK)
            drawBitmap(
                bitmap,
                matrix,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
        }
        return output
    }

    /**
     * Applies the automatic upright correction: a virtual camera rotation that
     * keeps the whole frame. With [keepCanvas] the result is the full warped
     * canvas (white where the source no longer covers it), which the editor
     * shows as a preview. Otherwise the result is the largest same-aspect
     * rectangle without empty corners, which is what gets saved.
     */
    fun upright(bitmap: Bitmap, geometry: AutoGeometry?, keepCanvas: Boolean = false): Bitmap {
        if (geometry == null) return bitmap
        val layout = geometry.layout(bitmap.width, bitmap.height) ?: return bitmap
        val crop = if (keepCanvas) {
            CropRect.FULL
        } else {
            val c = layout.constrainedCrop
            val guardX = 1f / layout.canvasWidth.coerceAtLeast(1)
            val guardY = 1f / layout.canvasHeight.coerceAtLeast(1)
            CropRect(
                left = c.left + guardX,
                top = c.top + guardY,
                right = c.right - guardX,
                bottom = c.bottom - guardY,
            )
        }
        if (crop.width <= 0f || crop.height <= 0f) return bitmap
        val values = layout.matrixFor(crop)
        if (abs(values[8]) < 1e-9) return bitmap
        val (outputWidth, outputHeight) = layout.outputSize(crop)
        if (outputWidth < 8 || outputHeight < 8) return bitmap
        val matrix = Matrix().apply {
            setValues(FloatArray(9) { (values[it] / values[8]).toFloat() })
        }
        val output = Bitmap.createBitmap(
            outputWidth.coerceAtMost(MAX_OUTPUT_DIMENSION),
            outputHeight.coerceAtMost(MAX_OUTPUT_DIMENSION),
            Bitmap.Config.ARGB_8888,
        )
        Canvas(output).apply {
            drawColor(Color.WHITE)
            drawBitmap(
                bitmap,
                matrix,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
        }
        return output
    }

    fun crop(bitmap: Bitmap, rect: CropRect): Bitmap {
        if (rect.isFull) return bitmap
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return bitmap
        val left = (rect.left * width).roundToInt().coerceIn(0, width - 1)
        val top = (rect.top * height).roundToInt().coerceIn(0, height - 1)
        val right = (rect.right * width).roundToInt().coerceIn(left + 1, width)
        val bottom = (rect.bottom * height).roundToInt().coerceIn(top + 1, height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    fun render(source: File, output: File, edits: PhotoEdits) {
        var current = decodeFull(source)
            ?: throw IOException("Could not decode the photo for editing")
        try {
            current = applyStep(current) { rotate(it, edits.normalizedRotation) }
            current = applyStep(current) { upright(it, edits.autoGeometry) }
            current = applyStep(current) { warp(it, edits.quad) }
            current = applyStep(current) { crop(it, edits.crop) }
            if (edits.autoEnhance > 0f) {
                current = applyStep(current) { AutoEnhance.apply(it, edits.autoEnhance) }
            }
            output.parentFile?.mkdirs()
            writeJpeg(current, output)
            PhotoProcessor.copyExif(source, output)
        } catch (exception: Exception) {
            output.delete()
            throw exception
        } finally {
            current.recycle()
        }
    }

    private fun decodeFull(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_OUTPUT_DIMENSION)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val side = max(decoded.width, decoded.height)
        if (side <= MAX_OUTPUT_DIMENSION) return decoded
        val scale = MAX_OUTPUT_DIMENSION.toFloat() / side
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).roundToInt().coerceAtLeast(1),
            (decoded.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private inline fun applyStep(current: Bitmap, transform: (Bitmap) -> Bitmap): Bitmap {
        val next = transform(current)
        if (next !== current) current.recycle()
        return next
    }

    private fun writeJpeg(bitmap: Bitmap, output: File) {
        FileOutputStream(output).use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                throw IOException("Could not encode the edited image")
            }
        }
    }

    private fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxDimension || height / (sample * 2) >= maxDimension) {
            sample *= 2
        }
        return sample
    }
}
