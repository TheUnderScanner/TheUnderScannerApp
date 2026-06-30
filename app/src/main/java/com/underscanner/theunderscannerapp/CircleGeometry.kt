package com.underscanner.theunderscannerapp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.*

object CircleGeometry {
    fun generateCircle(radius: Float, segments: Int, plane: String): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect((segments + 1) * 3 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        val angleStep = 2.0 * Math.PI / segments

        for (i in 0..segments) {
            val angle = i * angleStep
            val x = cos(angle).toFloat() * radius
            val y = sin(angle).toFloat() * radius

            when (plane) {
                "XY" -> buffer.put(x).put(y).put(0f)
                "YZ" -> buffer.put(0f).put(x).put(y)
                "ZX" -> buffer.put(x).put(0f).put(y)
            }
        }

        buffer.flip()
        return buffer
    }
}
