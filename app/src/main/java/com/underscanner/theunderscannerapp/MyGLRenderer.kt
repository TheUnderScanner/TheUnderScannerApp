package com.underscanner.theunderscannerapp

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

// Camera mode enum
enum class CameraMode {
    ORBIT,
    FPV
}

class MyGLRenderer(
    private val context: Context,
    private val fileName: String = "scan1.pcd",
    private val liveMode: Boolean = false
) : GLSurfaceView.Renderer {

    // Vertex buffer containing the point cloud vertices
    private lateinit var vertexBuffer: FloatBuffer
    // OpenGL shader program ID
    private var program = 0
    // Total number of points loaded from the PCD file
    private var pointCount: Int = 0

    // ----------------------------
    // Live preview (Phase 2)
    // ----------------------------
    // When set, points are drawn from this accumulating buffer instead of the file buffer.
    @Volatile
    var previewSource: PreviewCloud? = null
    // Current sensor position (world frame) to draw as a marker, or null.
    @Volatile
    private var poseMarker: FloatArray? = null

    fun setPoseMarker(x: Float, y: Float, z: Float) {
        poseMarker = floatArrayOf(x, y, z)
    }

    // ----------------------------
    // Camera Mode
    // ----------------------------
    private var _cameraMode: CameraMode = CameraMode.ORBIT
    val cameraMode: CameraMode
        get() = _cameraMode

    // ----------------------------
    // Orbit Camera Parameters
    // ----------------------------

    // The target point the camera is orbiting around
    private val orbitTarget = floatArrayOf(0f, 0f, 0f)
    private var orbitYaw = 0f          // in degrees
    private var orbitPitch = 20f       // in degrees
    private var orbitDistance = 20f    // distance from target

    // ----------------------------
    // FPV Camera Parameters
    // ----------------------------

    // Camera position in 3D space
    private val fpvPosition = floatArrayOf(0f, 0f, 20f)
    private var fpvYaw = 0f       // Looking direction (horizontal)
    private var fpvPitch = 0f     // Looking direction (vertical)
    private var fpvRoll = 0f      // Camera roll (for flight sim controls)

    // ----------------------------
    // Auto-Level Parameters
    // ----------------------------
    var autoLevelEnabled = false
    private var targetOrbitPitch = orbitPitch
    private val autoLevelLerpSpeed = 0.1f  // Smooth interpolation speed

    // ----------------------------
    // Matrices for rendering
    // ----------------------------

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    // Surface dimensions (used for aspect ratio calculation)
    private var surfaceWidth = 1
    private var surfaceHeight = 1

    // ----------------------------
    // Circle helpers (helps to understand the orientation of the whole scene)
    // ----------------------------

    private lateinit var circleXY: FloatBuffer
    private lateinit var circleYZ: FloatBuffer
    private lateinit var circleZX: FloatBuffer
    private val circleSegmentCount = 100 // Number of segments for each circle (smoothness)
    private val circleModelMatrix = FloatArray(16) // Transformation matrix for drawing circles

    // ----------------------------
    // Initialization block
    // ----------------------------

    init {
        if (liveMode) {
            // Live preview: no file. Points come from previewSource each frame.
            vertexBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asFloatBuffer()
            pointCount = 0
        } else {
            // Parse the PCD file and create the vertex buffer
            val parsed = parsePCD(context)
            vertexBuffer = parsed.first // The float buffer containing 3D points
            pointCount = parsed.second // The number of points parsed
            Log.d("PCD", "Loaded $pointCount points from $fileName")
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Set the background color to black (RGBA)
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        // Initialize 3D circles on the XY, YZ, and ZX planes for reference/grid visualization
        circleXY = CircleGeometry.generateCircle(1.0f, circleSegmentCount, "XY")
        circleYZ = CircleGeometry.generateCircle(1.0f, circleSegmentCount, "YZ")
        circleZX = CircleGeometry.generateCircle(1.0f, circleSegmentCount, "ZX")

        // Load and compile the vertex shader from asset file
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, readShader("shaders/vertex_shader.glsl"))
        // Load and compile the fragment shader from asset file
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, readShader("shaders/fragment_shader.glsl"))

        // Create an OpenGL program and attach the shaders
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)      // Attach compiled vertex shader
            GLES20.glAttachShader(it, fragmentShader)    // Attach compiled fragment shader
            GLES20.glLinkProgram(it)                     // Link the program (prepare it for use)
        }
    }


    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        // Update surface dimensions
        surfaceWidth = width
        surfaceHeight = height

        // Adjust the OpenGL viewport to the new surface size
        GLES20.glViewport(0, 0, width, height)
    }


    override fun onDrawFrame(gl: GL10?) {
        // Clear the screen (color and depth buffers)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // Use the compiled and linked shader program
        GLES20.glUseProgram(program)

        // --- Update camera view based on mode ---
        when (_cameraMode) {
            CameraMode.ORBIT -> updateOrbitCamera()
            CameraMode.FPV -> updateFPVCamera()
        }

        // --- Setup the perspective projection matrix ---

        val ratio = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 60f, ratio, 1f, 100f)

        // --- Create final MVP (Model-View-Projection) matrix ---

        Matrix.setIdentityM(modelMatrix, 0)
        val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, mvpMatrix, 0, modelMatrix, 0)

        // Pass the MVP matrix to the shader
        val mvpHandle = GLES20.glGetUniformLocation(program, "u_MVPMatrix")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)

        // --- Render the point cloud (file buffer, or the live preview when streaming) ---

        val source = previewSource
        val drawBuffer: FloatBuffer
        val drawCount: Int
        if (source != null) {
            drawBuffer = source.buffer
            drawCount = source.pointCount
        } else {
            drawBuffer = vertexBuffer
            drawCount = pointCount
        }

        val posHandle = GLES20.glGetAttribLocation(program, "a_Position")
        val colorHandle = GLES20.glGetUniformLocation(program, "u_Color")
        // Give points an explicit, visible color (the shader colors via u_Color).
        GLES20.glUniform4f(colorHandle, 0.85f, 0.9f, 1f, 1f)
        GLES20.glEnableVertexAttribArray(posHandle)
        drawBuffer.position(0)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, drawBuffer)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, drawCount)
        GLES20.glDisableVertexAttribArray(posHandle)

        // --- Draw the 3D axis circles at the origin ---
        if (_cameraMode == CameraMode.ORBIT) {
            // Reset model matrix and move to target position
            Matrix.setIdentityM(circleModelMatrix, 0)
            Matrix.translateM(circleModelMatrix, 0, orbitTarget[0], orbitTarget[1], orbitTarget[2])

            // Draw the circles in red (XY plane), green (YZ plane), and blue (ZX plane)
            drawCircle(circleXY, floatArrayOf(1f, 0f, 0f, 1f), circleModelMatrix) // XY - red
            drawCircle(circleYZ, floatArrayOf(0f, 1f, 0f, 1f), circleModelMatrix) // YZ - green
            drawCircle(circleZX, floatArrayOf(0f, 0f, 1f, 1f), circleModelMatrix) // ZX - blue
        }

        // --- Live preview: mark the current sensor position with reference circles ---
        poseMarker?.let { p ->
            Matrix.setIdentityM(circleModelMatrix, 0)
            Matrix.translateM(circleModelMatrix, 0, p[0], p[1], p[2])
            drawCircle(circleXY, floatArrayOf(1f, 0.85f, 0f, 1f), circleModelMatrix) // amber
            drawCircle(circleYZ, floatArrayOf(1f, 0.85f, 0f, 1f), circleModelMatrix)
            drawCircle(circleZX, floatArrayOf(1f, 0.85f, 0f, 1f), circleModelMatrix)
        }
    }

    // --- Camera Update Methods ---

    private fun updateOrbitCamera() {
        // Apply auto-level if enabled
        if (autoLevelEnabled) {
            // Smoothly interpolate pitch to horizontal (0 degrees)
            targetOrbitPitch = 0f
            val pitchDiff = targetOrbitPitch - orbitPitch
            orbitPitch += pitchDiff * autoLevelLerpSpeed
        }

        // Convert yaw and pitch from degrees to radians
        val yawRad = Math.toRadians(orbitYaw.toDouble()).toFloat()
        val pitchRad = Math.toRadians(orbitPitch.toDouble()).toFloat()

        // Calculate camera eye position in 3D space based on orbit parameters
        val eyeX = orbitTarget[0] + orbitDistance * cos(pitchRad) * sin(yawRad)
        val eyeY = orbitTarget[1] + orbitDistance * sin(pitchRad)
        val eyeZ = orbitTarget[2] + orbitDistance * cos(pitchRad) * cos(yawRad)

        // Set up vector to keep horizon level (always Y-up)
        val upY = if (cos(pitchRad) > 0) 1f else -1f

        // Create the view matrix based on camera position and orientation
        Matrix.setLookAtM(viewMatrix, 0, eyeX, eyeY, eyeZ, orbitTarget[0], orbitTarget[1], orbitTarget[2], 0f, upY, 0f)
    }

    private fun updateFPVCamera() {
        // Apply auto-level if enabled
        if (autoLevelEnabled) {
            // Smoothly interpolate pitch and roll to horizontal
            val pitchDiff = 0f - fpvPitch
            fpvPitch += pitchDiff * autoLevelLerpSpeed
            val rollDiff = 0f - fpvRoll
            fpvRoll += rollDiff * autoLevelLerpSpeed
        }

        // Convert angles from degrees to radians
        val yawRad = Math.toRadians(fpvYaw.toDouble()).toFloat()
        val pitchRad = Math.toRadians(fpvPitch.toDouble()).toFloat()
        val rollRad = Math.toRadians(fpvRoll.toDouble()).toFloat()

        // Calculate forward direction
        val lookAtX = fpvPosition[0] + cos(pitchRad) * sin(yawRad)
        val lookAtY = fpvPosition[1] + sin(pitchRad)
        val lookAtZ = fpvPosition[2] + cos(pitchRad) * cos(yawRad)

        // Calculate camera's up vector with roll
        // First get the base up vector (perpendicular to look direction)
        val baseUpX = -sin(pitchRad) * sin(yawRad)
        val baseUpY = cos(pitchRad)
        val baseUpZ = -sin(pitchRad) * cos(yawRad)

        // Calculate right vector (perpendicular to forward and up)
        val rightX = cos(yawRad)
        val rightY = 0f
        val rightZ = -sin(yawRad)

        // Apply roll rotation around the forward vector
        val upX = baseUpX * cos(rollRad) + rightX * sin(rollRad)
        val upY = baseUpY * cos(rollRad) + rightY * sin(rollRad)
        val upZ = baseUpZ * cos(rollRad) + rightZ * sin(rollRad)

        // Create the view matrix for FPV camera
        Matrix.setLookAtM(viewMatrix, 0,
            fpvPosition[0], fpvPosition[1], fpvPosition[2],  // Eye position
            lookAtX, lookAtY, lookAtZ,                        // Look at
            upX, upY, upZ)                                    // Up vector with roll
    }

    private fun readShader(name: String): String {
        // Read shader code from assets folder
        return context.assets.open(name).bufferedReader().use { it.readText() }
    }

    private fun loadShader(type: Int, code: String): Int {
        // Create and compile a shader of the given type (vertex or fragment)
        return GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, code)
            GLES20.glCompileShader(it)
        }
    }

    private fun parsePCD(context: Context): Pair<FloatBuffer, Int> {
        // Read the PCD file header from the local scans directory
        val reader = BufferedReader(InputStreamReader(openScanFile(context)))
        val headerLines = mutableListOf<String>()
        var line: String?

        // Read PCD header lines until we hit the DATA line
        while (true) {
            line = reader.readLine() ?: break
            headerLines.add(line)
            if (line.startsWith("DATA")) break
        }
        reader.close()

        // Extract the number of points from the header ("POINTS" line)
        val pointCount = headerLines.firstOrNull { it.startsWith("POINTS") }
            ?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: 0

        // Reopen to read the binary point cloud data
        val binaryStream = openScanFile(context)

        // Skip the header portion to reach binary data
        val skip = headerLines.joinToString("\n").toByteArray().size + 1
        binaryStream.skip(skip.toLong())

        // Read the point cloud data (each point is 8 floats of 4 bytes each: x, y, z, intensity...)
        val byteArray = ByteArray(pointCount * 8 * 4)
        binaryStream.read(byteArray)
        binaryStream.close()

        // Wrap the byte array into a FloatBuffer using little-endian byte order
        val sourceBuffer = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()

        // Prepare a FloatBuffer for OpenGL, storing only x, y, z (3 floats per point)
        // *NOTE* : Add intensity
        val floatBuffer = ByteBuffer.allocateDirect(pointCount * 3 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        // Populate floatBuffer with only x, y, z from the source buffer
        // *NOTE* : Add intensity (i*8+3)
        for (i in 0 until pointCount) {
            floatBuffer.put(sourceBuffer.get(i * 8))
            floatBuffer.put(sourceBuffer.get(i * 8 + 1))
            floatBuffer.put(sourceBuffer.get(i * 8 + 2))
        }
        floatBuffer.flip() // Finalize the buffer for use in OpenGL

        // Return the final buffer and the number of points
        return floatBuffer to pointCount
    }


    // ====Public Camera Control Methods=====

    // Switch camera mode and synchronize camera positions
    fun setCameraMode(mode: CameraMode) {
        if (_cameraMode != mode) {
            when (mode) {
                CameraMode.FPV -> {
                    // Switching to FPV: place FPV camera at current orbit camera position
                    val yawRad = Math.toRadians(orbitYaw.toDouble()).toFloat()
                    val pitchRad = Math.toRadians(orbitPitch.toDouble()).toFloat()

                    // Calculate current orbit camera eye position
                    fpvPosition[0] = orbitTarget[0] + orbitDistance * cos(pitchRad) * sin(yawRad)
                    fpvPosition[1] = orbitTarget[1] + orbitDistance * sin(pitchRad)
                    fpvPosition[2] = orbitTarget[2] + orbitDistance * cos(pitchRad) * cos(yawRad)

                    // Set FPV orientation to look at the orbit target
                    fpvYaw = orbitYaw
                    fpvPitch = orbitPitch
                }
                CameraMode.ORBIT -> {
                    // Switching to Orbit: center orbit on current FPV view direction
                    // Calculate where the FPV camera is looking
                    val yawRad = Math.toRadians(fpvYaw.toDouble()).toFloat()
                    val pitchRad = Math.toRadians(fpvPitch.toDouble()).toFloat()

                    // Set orbit target to a point in front of FPV camera
                    val lookDistance = 10f
                    orbitTarget[0] = fpvPosition[0] + lookDistance * cos(pitchRad) * sin(yawRad)
                    orbitTarget[1] = fpvPosition[1] + lookDistance * sin(pitchRad)
                    orbitTarget[2] = fpvPosition[2] + lookDistance * cos(pitchRad) * cos(yawRad)

                    // Set orbit orientation
                    orbitYaw = fpvYaw
                    orbitPitch = fpvPitch
                    orbitDistance = lookDistance
                }
            }
            _cameraMode = mode
        }
    }

    // --- Orbit Camera Controls ---

    // Rotate the orbit camera around the target based on user touch drag
    fun rotateOrbit(dYaw: Float, dPitch: Float) {
        orbitYaw += dYaw
        orbitPitch -= dPitch
        orbitPitch %= 360f // Keep pitch within 0-360 degrees
    }

    // Zoom the orbit camera in or out by adjusting the distance to the target
    fun zoomOrbitCamera(delta: Float) {
        orbitDistance -= delta
        orbitDistance = orbitDistance.coerceIn(1f, 100f) // Clamp zoom range
    }

    // Pan the orbit camera's target based on screen drag
    fun panOrbitTarget(dx: Float, dy: Float) {
        // Scale movement with distance
        val panSpeed = orbitDistance * 0.001f
        val yawRad = Math.toRadians(orbitYaw.toDouble()).toFloat()
        val right = floatArrayOf(
            -cos(yawRad),
            sin(yawRad),
            0f
        )
        val up = floatArrayOf(0f, 0f, 1f) // Up vector in Z-axis

        orbitTarget[0] += right[0] * dx * panSpeed + up[0] * dy * panSpeed
        orbitTarget[1] += right[1] * dx * panSpeed + up[1] * dy * panSpeed
        orbitTarget[2] += right[2] * dx * panSpeed + up[2] * dy * panSpeed
    }

    // --- FPV Camera Controls ---

    // Rotate FPV camera view direction (flight simulator style - camera relative)
    fun rotateFPV(dYaw: Float, dPitch: Float) {
        // Apply yaw rotation around camera's current up vector (affected by pitch)
        // In flight sim: yaw is relative to camera orientation, not world
        fpvYaw += dYaw * cos(Math.toRadians(fpvPitch.toDouble())).toFloat()

        // Apply pitch rotation
        fpvPitch -= dPitch
        fpvPitch = fpvPitch.coerceIn(-89f, 89f) // Prevent flipping

        // In flight mode, yaw wraps around naturally
        fpvYaw %= 360f
    }

    // Move FPV camera position
    fun moveFPV(forward: Float, right: Float, up: Float) {
        val yawRad = Math.toRadians(fpvYaw.toDouble()).toFloat()
        val pitchRad = Math.toRadians(fpvPitch.toDouble()).toFloat()

        // Forward/backward movement (along viewing direction, but only XZ plane)
        val forwardDir = floatArrayOf(
            sin(yawRad),
            0f,
            cos(yawRad)
        )

        // Right/left movement (perpendicular to forward)
        val rightDir = floatArrayOf(
            cos(yawRad),
            0f,
            -sin(yawRad)
        )

        // Apply movement
        val moveSpeed = 0.1f
        fpvPosition[0] += (forwardDir[0] * forward + rightDir[0] * right) * moveSpeed
        fpvPosition[1] += up * moveSpeed
        fpvPosition[2] += (forwardDir[2] * forward + rightDir[2] * right) * moveSpeed
    }


    // =====for Circles========

    // Draws a colored circle using the provided vertex buffer and model matrix
    fun drawCircle(buffer: FloatBuffer, color: FloatArray, modelMatrix: FloatArray) {
        val mvpMatrix = FloatArray(16) // Final combined Model-View-Projection matrix
        val tempMatrix = FloatArray(16) // Temporary matrix for intermediate calculations

        // Combine view and model matrices first
        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        // Then apply the projection matrix
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)

        // Get the shader attribute and uniform locations
        val positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
        val colorHandle = GLES20.glGetUniformLocation(program, "u_Color")
        val mvpHandle = GLES20.glGetUniformLocation(program, "u_MVPMatrix")

        // Pass the final MVP matrix to the shader
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)

        // Enable the position attribute and bind the circle vertex buffer
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, buffer)

        // Pass the color uniform to the shader
        GLES20.glUniform4fv(colorHandle, 1, color, 0)

        // Draw the circle as a connected loop of lines
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, circleSegmentCount + 1)

        // Disable the position attribute after drawing
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    // Getter to retrieve the number of points loaded from the point cloud file
    fun getPointCount(): Int {
        return pointCount
    }

    // Open the scan's .pcd from the shared local scans directory.
    private fun openScanFile(context: Context): java.io.InputStream {
        val file = File(LocalScanStorage.scansDir(context), fileName)
        if (!file.exists()) {
            throw FileNotFoundException("File not found: ${file.absolutePath}")
        }
        return FileInputStream(file)
    }

}
