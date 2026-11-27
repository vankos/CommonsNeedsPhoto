package com.example.commonsneedsphoto.data.photo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEnhanceTest {

    @Test
    fun strengthZeroIsIdentity() {
        val pixels = IntArray(100) { 0xFF303030.toInt() }
        val matrix = AutoEnhance.lerpWithIdentity(AutoEnhance.analyze(pixels), 0f)
        assertArrayEquals(AutoEnhance.identity(), matrix, 0.0001f)
    }

    @Test
    fun darkPhotoIsBrightened() {
        val pixels = IntArray(1000) { 0xFF282828.toInt() }
        val matrix = AutoEnhance.analyze(pixels)
        val (red, green, blue) = AutoEnhance.applyToArgb(matrix, 40, 40, 40)
        assertTrue(red > 40)
        assertEquals(red, green)
        assertEquals(red, blue)
    }

    @Test
    fun neutralGreyStaysNeutral() {
        val pixels = IntArray(100) { 0xFF808080.toInt() }
        val matrix = AutoEnhance.analyze(pixels)
        val (red, green, blue) = AutoEnhance.applyToArgb(matrix, 128, 128, 128)
        assertEquals(red, green)
        assertEquals(green, blue)
    }

    @Test
    fun redCastIsReduced() {
        val pixels = IntArray(1000) { 0xFFC86464.toInt() }
        val matrix = AutoEnhance.analyze(pixels)
        val (red, green, _) = AutoEnhance.applyToArgb(matrix, 200, 100, 100)
        assertTrue(red - green < 100)
    }

    @Test
    fun matrixHasTwentyValues() {
        val matrix = AutoEnhance.analyze(IntArray(10) { 0xFF404040.toInt() })
        assertEquals(20, matrix.size)
    }
}
