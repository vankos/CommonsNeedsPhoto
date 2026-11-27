package com.example.commonsneedsphoto.data.photo

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A point in image space, 0..1 from the left/top edge. Using normalised
 * coordinates keeps the editor state independent of the preview size and of
 * the full-resolution file.
 */
data class NormalizedPoint(val x: Float, val y: Float) {

    fun clamped(): NormalizedPoint =
        NormalizedPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}

enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

/**
 * Crop rectangle in normalised image coordinates.
 */
data class CropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    val isFull: Boolean
        get() = left <= 0.0001f && top <= 0.0001f &&
            right >= 0.9999f && bottom >= 0.9999f

    /**
     * Moves one corner while keeping the opposite corner fixed. When
     * [normalizedAspect] is set (width / height in normalised units), the
     * rectangle is resized to that ratio and kept inside the image.
     */
    fun withCorner(
        corner: Corner,
        point: NormalizedPoint,
        normalizedAspect: Float? = null,
    ): CropRect {
        val anchorX = if (corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT) right else left
        val anchorY = if (corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT) bottom else top
        val xSign = if (corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT) -1f else 1f
        val ySign = if (corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT) -1f else 1f
        val maxWidth = if (xSign < 0f) anchorX else 1f - anchorX
        val maxHeight = if (ySign < 0f) anchorY else 1f - anchorY
        if (maxWidth <= 0f || maxHeight <= 0f) return this

        val px = point.x.coerceIn(0f, 1f)
        val py = point.y.coerceIn(0f, 1f)
        var width = if (xSign < 0f) anchorX - px else px - anchorX
        var height = if (ySign < 0f) anchorY - py else py - anchorY
        width = width.coerceIn(0f, maxWidth)
        height = height.coerceIn(0f, maxHeight)

        if (normalizedAspect != null && normalizedAspect > 0f) {
            if (width <= 0f || height <= 0f) {
                width = MIN_SIZE.coerceAtMost(maxWidth)
                height = MIN_SIZE.coerceAtMost(maxHeight)
            }
            if (width / height > normalizedAspect) {
                height = width / normalizedAspect
            } else {
                width = height * normalizedAspect
            }
            if (width > maxWidth) {
                width = maxWidth
                height = width / normalizedAspect
            }
            if (height > maxHeight) {
                height = maxHeight
                width = height * normalizedAspect
            }
        }

        width = width.coerceAtLeast(MIN_SIZE.coerceAtMost(maxWidth))
        height = height.coerceAtLeast(MIN_SIZE.coerceAtMost(maxHeight))
        width = width.coerceAtMost(maxWidth)
        height = height.coerceAtMost(maxHeight)

        val x = anchorX + xSign * width
        val y = anchorY + ySign * height
        return CropRect(
            left = if (xSign < 0f) x else left,
            top = if (ySign < 0f) y else top,
            right = if (xSign < 0f) right else x,
            bottom = if (ySign < 0f) bottom else y,
        )
    }

    fun translated(dx: Float, dy: Float): CropRect {
        val w = width
        val h = height
        if (w <= 0f || h <= 0f) return this
        val x = (left + dx).coerceIn(0f, 1f - w)
        val y = (top + dy).coerceIn(0f, 1f - h)
        return CropRect(x, y, x + w, y + h)
    }

    companion object {
        const val MIN_SIZE = 0.05f

        val FULL = CropRect(0f, 0f, 1f, 1f)

        /**
         * The largest centred rectangle with the requested pixel aspect
         * (width / height) inside an image of [imageWidth] x [imageHeight].
         */
        fun centered(pixelAspect: Float, imageWidth: Int, imageHeight: Int): CropRect {
            if (pixelAspect <= 0f || imageWidth <= 0 || imageHeight <= 0) return FULL
            val normalizedAspect = pixelAspect * imageHeight / imageWidth
            val width: Float
            val height: Float
            if (normalizedAspect >= 1f) {
                width = 1f
                height = 1f / normalizedAspect
            } else {
                height = 1f
                width = normalizedAspect
            }
            return CropRect(
                left = (1f - width) / 2f,
                top = (1f - height) / 2f,
                right = (1f + width) / 2f,
                bottom = (1f + height) / 2f,
            )
        }
    }
}

/**
 * Four corners of the area that should be rectified into a straight-on image.
 * Corners are normalised and ordered clockwise starting at the top-left.
 */
data class PerspectiveQuad(
    val topLeft: NormalizedPoint,
    val topRight: NormalizedPoint,
    val bottomRight: NormalizedPoint,
    val bottomLeft: NormalizedPoint,
) {
    val corners: List<NormalizedPoint> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun withCorner(corner: Corner, point: NormalizedPoint): PerspectiveQuad {
        val p = point.clamped()
        return when (corner) {
            Corner.TOP_LEFT -> copy(topLeft = p)
            Corner.TOP_RIGHT -> copy(topRight = p)
            Corner.BOTTOM_RIGHT -> copy(bottomRight = p)
            Corner.BOTTOM_LEFT -> copy(bottomLeft = p)
        }
    }

    fun area(): Float {
        val points = corners
        var sum = 0f
        for (i in 0 until points.size) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2f
    }

    /** True when every turn goes the same way, i.e. the outline does not fold. */
    fun isConvex(): Boolean {
        val points = corners
        var positive = false
        var negative = false
        for (i in 0 until points.size) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val c = points[(i + 2) % points.size]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross > 1e-6f) positive = true
            if (cross < -1e-6f) negative = true
            if (positive && negative) return false
        }
        return !(positive && negative) && area() > 1e-5f
    }

    fun isUsable(): Boolean = isConvex() && area() >= MIN_AREA

    fun isFullFrame(epsilon: Float = 0.001f): Boolean = corners.indices.all { index ->
        val expected = FULL_FRAME.corners[index]
        val actual = corners[index]
        abs(expected.x - actual.x) <= epsilon && abs(expected.y - actual.y) <= epsilon
    }

    /**
     * Size of the rectified image: the longer of the two opposite edges,
     * so the warp does not lose detail.
     */
    fun warpedSize(imageWidth: Int, imageHeight: Int): Pair<Int, Int> {
        fun distance(a: NormalizedPoint, b: NormalizedPoint): Float =
            hypot((a.x - b.x) * imageWidth, (a.y - b.y) * imageHeight)

        val width = max(
            distance(topLeft, topRight),
            distance(bottomLeft, bottomRight),
        ).roundToInt().coerceIn(1, MAX_WARP_DIMENSION)
        val height = max(
            distance(topLeft, bottomLeft),
            distance(topRight, bottomRight),
        ).roundToInt().coerceIn(1, MAX_WARP_DIMENSION)
        return width to height
    }

    companion object {
        const val MIN_AREA = 0.01f
        private const val MAX_WARP_DIMENSION = 8192

        val FULL_FRAME = PerspectiveQuad(
            topLeft = NormalizedPoint(0f, 0f),
            topRight = NormalizedPoint(1f, 0f),
            bottomRight = NormalizedPoint(1f, 1f),
            bottomLeft = NormalizedPoint(0f, 1f),
        )

        /** Puts four unordered points into the top-left/top-right/bottom-right/bottom-left order. */
        fun ordered(points: List<NormalizedPoint>): PerspectiveQuad {
            require(points.size == 4) { "A quad needs four points" }
            return PerspectiveQuad(
                topLeft = points.minBy { it.x + it.y },
                topRight = points.maxBy { it.x - it.y },
                bottomRight = points.maxBy { it.x + it.y },
                bottomLeft = points.minBy { it.x - it.y },
            )
        }
    }
}

/**
 * Everything the editor does to a photo. Kept as plain data so it can live in
 * the ViewModel across rotation and so the renderer is easy to test.
 */
data class PhotoEdits(
    val rotationDegrees: Int = 0,
    val autoGeometry: AutoGeometry? = null,
    val quad: PerspectiveQuad? = null,
    val crop: CropRect = CropRect.FULL,
    /** 0 = off, 1 = full automatic correction. */
    val autoEnhance: Float = 0f,
) {
    val normalizedRotation: Int
        get() = ((rotationDegrees % 360) + 360) % 360

    val hasEdits: Boolean
        get() = normalizedRotation != 0 ||
            autoGeometry != null ||
            (quad != null && !quad.isFullFrame()) ||
            !crop.isFull ||
            autoEnhance > 0f
}
