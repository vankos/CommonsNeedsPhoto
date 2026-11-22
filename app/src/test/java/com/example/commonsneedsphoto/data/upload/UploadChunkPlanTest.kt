package com.example.commonsneedsphoto.data.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadChunkPlanTest {

    private val chunk = 512

    @Test
    fun `a file smaller than one chunk is a single chunk`() {
        val plan = UploadChunkPlan(totalBytes = 100, chunkSize = chunk)

        assertEquals(1, plan.chunkCount)
        assertEquals(0L, plan.offsetOf(0))
        assertEquals(100, plan.lengthOf(0))
        assertEquals(UploadChunk(index = 0, offset = 0, length = 100), plan.chunkAt(0))
        assertNull(plan.chunkAt(100))
        assertTrue(plan.isComplete(100))
        assertFalse(plan.isComplete(99))
    }

    @Test
    fun `an exact multiple of the chunk size has no empty final chunk`() {
        val plan = UploadChunkPlan(totalBytes = (chunk * 3).toLong(), chunkSize = chunk)

        assertEquals(3, plan.chunkCount)
        assertEquals(1024L, plan.offsetOf(2))
        assertEquals(512, plan.lengthOf(2))
        assertNull(plan.chunkAt((chunk * 3).toLong()))
    }

    @Test
    fun `the final chunk keeps the remainder`() {
        val plan = UploadChunkPlan(totalBytes = (chunk * 2 + 123).toLong(), chunkSize = chunk)

        assertEquals(3, plan.chunkCount)
        assertEquals(123, plan.lengthOf(2))
        assertEquals(1024L, plan.chunkAt(1024)!!.offset)
        assertEquals(123, plan.chunkAt(1024)!!.length)
        assertNull(plan.chunkAt((chunk * 2 + 123).toLong()))
    }

    @Test
    fun `a persisted offset produces the next chunk`() {
        val plan = UploadChunkPlan(totalBytes = 10_000, chunkSize = chunk)

        val next = plan.chunkAt(plan.offsetOf(1))

        assertEquals(1, next!!.index)
        assertEquals(512L, next.offset)
        assertEquals(512, next.length)
        assertTrue(plan.isAligned(next.offset))
        assertTrue(plan.isAligned(0))
        assertTrue(plan.isAligned(10_000))
        assertFalse(plan.isAligned(100))
    }

    @Test
    fun `chunk size must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            UploadChunkPlan(totalBytes = 10, chunkSize = 0)
        }
    }

    @Test
    fun `the default chunk size matches the MediaWiki stash protocol`() {
        assertEquals(512 * 1024, UploadChunkPlan(totalBytes = 1024).chunkSize)
    }
}
