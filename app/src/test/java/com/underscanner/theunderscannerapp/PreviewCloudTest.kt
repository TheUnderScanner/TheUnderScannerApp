package com.underscanner.theunderscannerapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PreviewCloudTest {

    @Test
    fun longHashSet_dedupsAndGrows() {
        val s = LongHashSet(4)
        for (k in 0L until 10_000L) assertTrue(s.add(k * 7919))
        for (k in 0L until 10_000L) assertFalse(s.add(k * 7919))
        assertEquals(10_000, s.size)
        s.clear()
        assertEquals(0, s.size)
        assertTrue(s.add(0L))
    }

    /** One USC1 record block of [n] points along a line, [spacing] m apart. */
    private fun frame(n: Int, spacing: Float, x0: Float = 0f): ByteBuffer {
        val b = ByteBuffer.allocate(n * PreviewCloud.STRIDE).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            b.putFloat(x0 + i * spacing).putFloat(0.05f).putFloat(0.05f)
            b.put(i.toByte()).put(0).put(0).put(0)
        }
        b.flip()
        return b
    }

    @Test
    fun cappedCloud_coarsensInsteadOfFreezing() {
        val cloud = PreviewCloud(capacity = 1_000)
        cloud.addFrame(frame(1_000, 0.1f), 1_000)
        val genBefore = cloud.generation
        // A new area: before the fix these were all dropped once the cap was hit.
        cloud.addFrame(frame(500, 0.1f, x0 = 1_000f), 500)

        assertTrue(cloud.voxelSize > 0.1f)
        assertTrue(cloud.generation > genBefore)
        assertFalse(cloud.capped)
        assertTrue(cloud.pointCount <= 1_000)
        // The new area made it in: the last kept record lies in it.
        val last = (cloud.pointCount - 1) * PreviewCloud.STRIDE
        assertTrue(cloud.byteBuffer.order(ByteOrder.nativeOrder()).getFloat(last) >= 1_000f)
    }

    @Test
    fun clear_resetsVoxelAndBumpsGeneration() {
        val cloud = PreviewCloud(capacity = 1_000)
        cloud.addFrame(frame(1_500, 0.1f), 1_500)
        val gen = cloud.generation
        cloud.clear()
        assertEquals(0, cloud.pointCount)
        assertEquals(0.1f, cloud.voxelSize, 1e-6f)
        assertTrue(cloud.generation > gen)
    }
}
