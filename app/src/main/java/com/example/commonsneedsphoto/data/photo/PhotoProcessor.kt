package com.example.commonsneedsphoto.data.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Turns a picked/captured image into a Commons-friendly JPEG: bounded size,
 * upright orientation, preserved camera EXIF and a GPS location.
 */
object PhotoProcessor {

    private const val MAX_DIMENSION = 4096
    private const val MAX_FILE_BYTES = 20L * 1024 * 1024
    private const val JPEG_QUALITY = 92

    private val copiedExifTags = listOf(
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_EXIF_VERSION,
        ExifInterface.TAG_SOFTWARE,
    )

    fun createCameraTarget(context: Context): File {
        val dir = File(context.filesDir, "photos").apply { mkdirs() }
        return File(dir, "camera_${System.currentTimeMillis()}.jpg")
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)

    /**
     * @param fallbackCoordinates coordinates of the place being photographed,
     * written into EXIF when the camera did not provide GPS.
     */
    fun process(
        context: Context,
        source: Uri,
        fallbackCoordinates: Pair<Double, Double>?,
    ): File {
        val outputDir = File(context.filesDir, "photos").apply { mkdirs() }
        val output = File(outputDir, "upload_${System.currentTimeMillis()}.jpg")

        val resolver = context.contentResolver
        val orientation = resolver.openInputStream(source)?.use { stream ->
            runCatching {
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("The selected file is not a readable image")
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IOException("Could not decode the selected image")

        val upright = applyOrientation(decoded, orientation)
        val scaled = scaleDown(upright, MAX_DIMENSION)
        if (scaled !== decoded) decoded.recycle()
        if (upright !== decoded && upright !== scaled) upright.recycle()

        writeJpeg(scaled, output, JPEG_QUALITY)
        scaled.recycle()

        if (output.length() > MAX_FILE_BYTES) {
            val smaller = decodeScaled(output, 2560)
            if (smaller != null) {
                writeJpeg(smaller, output, 84)
                smaller.recycle()
            }
        }

        copyExif(context, source, output, fallbackCoordinates)
        return output
    }

    private fun writeJpeg(bitmap: Bitmap, output: File, quality: Int) {
        FileOutputStream(output).use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                throw IOException("Could not encode the image")
            }
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxDimension || height / (sample * 2) >= maxDimension) {
            sample *= 2
        }
        return sample
    }

    private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / maxSide
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun decodeScaled(file: File, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private fun copyExif(
        context: Context,
        source: Uri,
        output: File,
        fallbackCoordinates: Pair<Double, Double>?,
    ) {
        runCatching {
            val sourceExif = context.contentResolver.openInputStream(source)?.use {
                ExifInterface(it)
            } ?: return
            val outputExif = ExifInterface(output.absolutePath)
            for (tag in copiedExifTags) {
                sourceExif.getAttribute(tag)?.let { outputExif.setAttribute(tag, it) }
            }
            outputExif.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL.toString(),
            )
            val existing = sourceExif.latLong
            if (existing != null) {
                outputExif.setLatLong(existing[0], existing[1])
            } else if (fallbackCoordinates != null) {
                outputExif.setLatLong(fallbackCoordinates.first, fallbackCoordinates.second)
            }
            outputExif.saveAttributes()
        }
    }

    fun clearCache(context: Context) {
        val dir = File(context.filesDir, "photos")
        if (!dir.exists()) return
        dir.listFiles()?.forEach { it.delete() }
    }
}
