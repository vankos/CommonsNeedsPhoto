package com.example.commonsneedsphoto.data.photo

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LineSegmentDetectorTest {

    @Test
    fun blankImageHasNoSegments() {
        val width = 200
        val height = 150
        val grey = FloatArray(width * height) { 128f }
        assertTrue(LineSegmentDetector.detect(grey, width, height).isEmpty())
    }

    @Test
    fun rectangleEdgesAreFound() {
        val width = 240
        val height = 180
        val pixels = IntArray(width * height) { 0xFF303030.toInt() }
        for (y in 40 until 140) {
            for (x in 40 until 200) {
                pixels[y * width + x] = 0xFFFFFFFF.toInt()
            }
        }
        val segments = LineSegmentDetector.detect(
            LineSegmentDetector.greyscale(pixels, width, height),
            width,
            height,
        )
        assertTrue("expected segments, got none", segments.isNotEmpty())
        assertTrue(
            "expected an almost horizontal edge",
            segments.any { abs(it.angleFromHorizontal()) < Math.toRadians(2.0) },
        )
        assertTrue(
            "expected an almost vertical edge",
            segments.any { abs(it.angleFromVertical()) < Math.toRadians(2.0) },
        )
    }
}
