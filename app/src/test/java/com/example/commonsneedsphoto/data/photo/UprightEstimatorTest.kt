package com.example.commonsneedsphoto.data.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class UprightEstimatorTest {

    @Test
    fun syntheticKeystoneIsRecovered() {
        val width = 771
        val height = 1024
        val result = estimate(3.0, 15.0, 8.0, width, height)
        assertTrue("expected a corrected result, got $result", result is UprightResult.Corrected)
        val geometry = (result as UprightResult.Corrected).geometry
        assertEquals(UprightMode.FULL, geometry.mode)
        assertEquals(3.0, Math.toDegrees(geometry.roll), 0.05)
        assertEquals(15.0, Math.toDegrees(geometry.pitch), 0.05)
        assertEquals(8.0, Math.toDegrees(geometry.yaw), 0.05)
    }

    @Test
    fun syntheticPitchIsCorrectedVertically() {
        val result = estimate(0.0, 12.0, 0.0, 771, 1024)
        assertTrue(result is UprightResult.Corrected)
        val geometry = (result as UprightResult.Corrected).geometry
        assertEquals(UprightMode.VERTICAL, geometry.mode)
        assertEquals(12.0, Math.toDegrees(geometry.pitch), 0.05)
    }

    @Test
    fun syntheticRollBecomesLevel() {
        val result = estimate(2.0, 0.0, 0.0, 771, 1024)
        assertTrue(result is UprightResult.Corrected)
        val geometry = (result as UprightResult.Corrected).geometry
        assertEquals(UprightMode.LEVEL, geometry.mode)
        assertEquals(2.0, Math.toDegrees(geometry.roll), 0.05)
    }

    @Test
    fun emptySceneIsNotFound() {
        val result = UprightEstimator.estimate(emptyList(), 771, 1024)
        assertEquals(UprightResult.Reason.NO_LINES, (result as UprightResult.NotFound).reason)
    }

    private fun estimate(
        roll: Double,
        pitch: Double,
        yaw: Double,
        width: Int,
        height: Int,
    ): UprightResult {
        val truth = AutoGeometry(
            mode = UprightMode.FULL,
            roll = rad(roll),
            pitch = rad(pitch),
            yaw = rad(yaw),
            shear = 0.0,
            focalRatio = 0.70,
        )
        return UprightEstimator.estimate(scene(truth, width, height), width, height)
    }

    private fun scene(truth: AutoGeometry, width: Int, height: Int): List<LineSegment> {
        val inverse = Mat3.inverse(truth.centredMatrix(width, height))
        val segments = ArrayList<LineSegment>()
        fun add(x1: Double, y1: Double, x2: Double, y2: Double) {
            val a = Mat3.map(inverse, x1, y1) ?: return
            val b = Mat3.map(inverse, x2, y2) ?: return
            segments += LineSegment(
                x1 = a[0] + width / 2.0,
                y1 = a[1] + height / 2.0,
                x2 = b[0] + width / 2.0,
                y2 = b[1] + height / 2.0,
                length = hypot(b[0] - a[0], b[1] - a[1]),
            )
        }
        for (index in 0 until 8) {
            val x = (-0.4 + 0.8 * index / 7.0) * width
            add(
                x,
                -0.15 * height - 0.2 * height * (index % 2),
                x,
                0.15 * height + 0.2 * height * ((index + 1) % 2),
            )
        }
        for (index in 0 until 8) {
            val y = (-0.4 + 0.8 * index / 7.0) * height
            add(
                -0.15 * width - 0.2 * width * (index % 2),
                y,
                0.15 * width + 0.2 * width * ((index + 1) % 2),
                y,
            )
        }
        return segments
    }

    private fun rad(degrees: Double) = Math.toRadians(degrees)
}
