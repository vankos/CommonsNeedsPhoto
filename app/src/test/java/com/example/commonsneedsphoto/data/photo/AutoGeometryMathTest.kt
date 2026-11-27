package com.example.commonsneedsphoto.data.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class AutoGeometryMathTest {

    @Test
    fun largestAspectRectMatchesRotationFormula() {
        val angle = Math.toRadians(2.0)
        val width = 3072.0
        val height = 4080.0
        val corners = doubleArrayOf(
            -width / 2, -height / 2,
            width / 2, -height / 2,
            width / 2, height / 2,
            -width / 2, height / 2,
        )
        val rotated = DoubleArray(8) { index ->
            val x = corners[index - index % 2]
            val y = corners[index - index % 2 + 1]
            if (index % 2 == 0) x * cos(angle) - y * sin(angle)
            else x * sin(angle) + y * cos(angle)
        }
        val crop = UprightFraming.largestAspectRectInQuad(rotated, width / height)
        assertNotNull(crop)
        val expected = 1.0 / (cos(angle) + height / width * sin(angle))
        assertEquals(expected, (crop!![2] - crop[0]) / width, 1e-6)
    }

    @Test
    fun anglesRoundTrip() {
        for (roll in listOf(-12.0, 0.0, 7.0)) {
            for (pitch in listOf(-9.0, 0.0, 11.0)) {
                for (yaw in listOf(-8.0, 0.0, 6.0)) {
                    val matrix = Mat3.rotation(rad(roll), rad(pitch), rad(yaw))
                    // R^T maps the basis vectors to the observed vanishing
                    // directions: rows of R, not columns.
                    val vertical = doubleArrayOf(matrix[3], matrix[4], matrix[5])
                    val horizontal = doubleArrayOf(matrix[0], matrix[1], matrix[2])
                    val angles = UprightEstimator.anglesFromDirections(vertical, horizontal)
                    assertEquals(roll, Math.toDegrees(angles[0]), 1e-9)
                    assertEquals(pitch, Math.toDegrees(angles[1]), 1e-9)
                    assertEquals(yaw, Math.toDegrees(angles[2]), 1e-9)
                }
            }
        }
    }

    @Test
    fun identityLayoutKeepsTheFrame() {
        val geometry = AutoGeometry(
            mode = UprightMode.LEVEL,
            roll = 0.0,
            pitch = 0.0,
            yaw = 0.0,
            shear = 0.0,
            focalRatio = 0.70,
        )
        val layout = geometry.layout(771, 1024)
        assertNotNull(layout)
        assertTrue(layout!!.sourceKept > 0.99)
        assertTrue(layout.cover > 0.99)
        assertTrue(layout.constrainedCrop.width > 0.99f)
        assertTrue(layout.constrainedCrop.height > 0.99f)
    }

    private fun rad(degrees: Double) = Math.toRadians(degrees)
}
