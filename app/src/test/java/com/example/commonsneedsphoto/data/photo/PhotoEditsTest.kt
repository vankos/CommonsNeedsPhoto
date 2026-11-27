package com.example.commonsneedsphoto.data.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoEditsTest {

    @Test
    fun centeredCropKeepsRequestedAspect() {
        val crop = CropRect.centered(pixelAspect = 1f, imageWidth = 400, imageHeight = 200)
        // Normalised aspect = 1 * 200 / 400 = 0.5.
        assertEquals(0.5f, crop.width, 0.0001f)
        assertEquals(1f, crop.height, 0.0001f)
        assertEquals(0.25f, crop.left, 0.0001f)
        assertEquals(0f, crop.top, 0.0001f)
    }

    @Test
    fun centeredCropFitsWideImage() {
        val crop = CropRect.centered(
            pixelAspect = 16f / 9f,
            imageWidth = 4000,
            imageHeight = 3000,
        )
        // Normalised aspect = 16/9 * 3000/4000 = 4/3.
        assertEquals(1f, crop.width, 0.0001f)
        assertEquals(0.75f, crop.height, 0.0001f)
        assertEquals(0.125f, crop.top, 0.0001f)
    }

    @Test
    fun resizeCornerKeepsAspectAndOppositeCorner() {
        val crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f)
        val resized = crop.withCorner(
            corner = Corner.BOTTOM_RIGHT,
            point = NormalizedPoint(0.85f, 0.6f),
            normalizedAspect = 2f,
        )
        assertEquals(0.1f, resized.left, 0.0001f)
        assertEquals(0.1f, resized.top, 0.0001f)
        assertEquals(2f, resized.width / resized.height, 0.01f)
        assertTrue(resized.right <= 1f)
        assertTrue(resized.bottom <= 1f)
    }

    @Test
    fun resizeCornerWithAspectFitsInsideImage() {
        val crop = CropRect(0.5f, 0.5f, 0.9f, 0.9f)
        val resized = crop.withCorner(
            corner = Corner.BOTTOM_LEFT,
            point = NormalizedPoint(0f, 1f),
            normalizedAspect = 4f,
        )
        assertEquals(0.9f, resized.right, 0.0001f)
        assertEquals(0.5f, resized.top, 0.0001f)
        assertEquals(4f, resized.width / resized.height, 0.01f)
        assertTrue(resized.left >= 0f)
        assertTrue(resized.bottom <= 1f)
    }

    @Test
    fun translatedCropStaysInside() {
        val crop = CropRect(0.2f, 0.2f, 0.8f, 0.8f)
        val moved = crop.translated(0.5f, 0.5f)
        assertEquals(0.4f, moved.left, 0.0001f)
        assertEquals(0.4f, moved.top, 0.0001f)
        assertEquals(1f, moved.right, 0.0001f)
        assertEquals(1f, moved.bottom, 0.0001f)
    }

    @Test
    fun fullFrameQuadIsConvexAndFull() {
        val quad = PerspectiveQuad.FULL_FRAME
        assertTrue(quad.isConvex())
        assertTrue(quad.isFullFrame())
        assertTrue(quad.isUsable())
        assertEquals(1f, quad.area(), 0.0001f)
    }

    @Test
    fun crossedQuadIsNotUsable() {
        val quad = PerspectiveQuad.FULL_FRAME.copy(topLeft = NormalizedPoint(1f, 1f))
        assertFalse(quad.isUsable())
    }

    @Test
    fun orderedQuadFindsOuterCorners() {
        val quad = PerspectiveQuad.ordered(
            listOf(
                NormalizedPoint(0.1f, 0.8f),
                NormalizedPoint(0.8f, 0.1f),
                NormalizedPoint(0.9f, 0.9f),
                NormalizedPoint(0.2f, 0.2f),
            ),
        )
        assertEquals(0.2f, quad.topLeft.x, 0.0001f)
        assertEquals(0.2f, quad.topLeft.y, 0.0001f)
        assertEquals(0.8f, quad.topRight.x, 0.0001f)
        assertEquals(0.1f, quad.topRight.y, 0.0001f)
        assertEquals(0.9f, quad.bottomRight.x, 0.0001f)
        assertEquals(0.9f, quad.bottomRight.y, 0.0001f)
        assertEquals(0.1f, quad.bottomLeft.x, 0.0001f)
        assertEquals(0.8f, quad.bottomLeft.y, 0.0001f)
    }

    @Test
    fun warpedSizeUsesLongerEdges() {
        val quad = PerspectiveQuad(
            topLeft = NormalizedPoint(0f, 0f),
            topRight = NormalizedPoint(0.5f, 0f),
            bottomRight = NormalizedPoint(0.5f, 0.5f),
            bottomLeft = NormalizedPoint(0f, 0.5f),
        )
        val (width, height) = quad.warpedSize(2000, 1000)
        assertEquals(1000, width)
        assertEquals(500, height)
    }
}
