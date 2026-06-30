package com.underscanner.theunderscannerapp

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
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

/**
 * OpenGL ES 2.0 point-cloud renderer. The vertex/parse/draw pipeline is unchanged from
 * the original viewer; the camera is the new single-state [OrbitCamera] (see that file).
 *
 * Touch gestures (in [MyGLSurfaceView]) call the `cam*` / `teleport*` / `frameAll`
 * methods here via `queueEvent`, so all camera mutation happens on the GL thread — no
 * locking is needed against the draw loop.
 */
class MyGLRenderer(
    private val context: Context,
    private val fileName: String = "scan1.pcd",
    private val liveMode: Boolean = false
) : GLSurfaceView.Renderer {

    // Vertex buffer containing the point cloud vertices (file mode)
    private lateinit var vertexBuffer: FloatBuffer
    private var program = 0
    private var pointCount: Int = 0

    // ----------------------------
    // Live preview (Phase 2)
    // ----------------------------
    @Volatile
    var previewSource: PreviewCloud? = null

    @Volatile
    private var poseMarker: FloatArray? = null

    fun setPoseMarker(x: Float, y: Float, z: Float) {
        poseMarker = floatArrayOf(x, y, z)
    }

    // ----------------------------
    // Camera
    // ----------------------------
    val camera = OrbitCamera()

    // Scene bounds for frame-all (file mode computed once at load).
    private var hasBounds = false
    private val boundsMin = floatArrayOf(0f, 0f, 0f)
    private val boundsMax = floatArrayOf(0f, 0f, 0f)
    private var pendingInitialFit = false

    // ----------------------------
    // Matrices
    // ----------------------------
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var lastFrameTimeMs = 0L

    // ----------------------------
    // Reference circles + teleport helper
    // ----------------------------
    private lateinit var circleXY: FloatBuffer
    private lateinit var circleYZ: FloatBuffer
    private lateinit var circleZX: FloatBuffer
    private val circleSegmentCount = 100
    private val circleModelMatrix = FloatArray(16)

    // Axis ruler ("repères") — yellow world-axis lines through the pivot, shown while the
    // pivot is moving (or always, when toggled on). Decoupled from gestures: see AxisRuler.
    private lateinit var rulerBuffer: FloatBuffer
    private val prevPivot = FloatArray(3)
    private var pivotMovingUntilMs = 0L
    private var showHelpersAlways = false
    private val HELPER_FADE_MS = 700L // keep the ruler up briefly after motion stops

    init {
        if (liveMode) {
            vertexBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asFloatBuffer()
            pointCount = 0
            camera.distance = 12f
        } else {
            val parsed = parsePCD(context)
            vertexBuffer = parsed.first
            pointCount = parsed.second
            Log.d("PCD", "Loaded $pointCount points from $fileName")
            if (hasBounds) {
                applyBoundsToCamera()
                pendingInitialFit = true // fit once the surface (aspect) is known
            }
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        circleXY = CircleGeometry.generateCircle(1.0f, circleSegmentCount, "XY")
        circleYZ = CircleGeometry.generateCircle(1.0f, circleSegmentCount, "YZ")
        circleZX = CircleGeometry.generateCircle(1.0f, circleSegmentCount, "ZX")
        rulerBuffer = AxisRuler.newBuffer()

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, readShader("shaders/vertex_shader.glsl"))
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, readShader("shaders/fragment_shader.glsl"))
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Frame delta for animations.
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameTimeMs == 0L) 16f else (now - lastFrameTimeMs).toFloat()
        lastFrameTimeMs = now

        if (pendingInitialFit && surfaceWidth > 1) {
            camera.frame(aspect(), animate = false)
            pendingInitialFit = false
        }
        camera.update(dt)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)

        camera.viewMatrix(viewMatrix)
        camera.projectionMatrix(projectionMatrix, aspect())

        // --- MVP for the point cloud (identity model) ---
        Matrix.setIdentityM(modelMatrix, 0)
        val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, mvpMatrix, 0, modelMatrix, 0)
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
        GLES20.glUniform4f(colorHandle, 0.85f, 0.9f, 1f, 1f)
        GLES20.glEnableVertexAttribArray(posHandle)
        drawBuffer.position(0)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, drawBuffer)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, drawCount)
        GLES20.glDisableVertexAttribArray(posHandle)

        // --- Reference circles at the pivot (scaled to stay visible across the range) ---
        val pivotScale = camera.camDist() * 0.07f
        drawMarkerCircles(camera.pivot, pivotScale,
            floatArrayOf(1f, 0f, 0f, 1f), floatArrayOf(0f, 1f, 0f, 1f), floatArrayOf(0f, 0f, 1f, 1f))

        // --- Live preview: current sensor pose marker (amber) ---
        poseMarker?.let { p ->
            val amber = floatArrayOf(1f, 0.85f, 0f, 1f)
            drawMarkerCircles(p, camera.camDist() * 0.05f, amber, amber, amber)
        }

        // --- Axis ruler: show whenever the pivot is moving (or always, if toggled). ---
        // Driven purely by detecting pivot motion, so any code that moves the pivot lights it up.
        if (pivotMoved()) pivotMovingUntilMs = now + HELPER_FADE_MS
        if (showHelpersAlways || now < pivotMovingUntilMs) drawRuler()
    }

    private fun pivotMoved(): Boolean {
        val dx = camera.pivot[0] - prevPivot[0]
        val dy = camera.pivot[1] - prevPivot[1]
        val dz = camera.pivot[2] - prevPivot[2]
        val moved = dx * dx + dy * dy + dz * dz > 1e-9f
        prevPivot[0] = camera.pivot[0]; prevPivot[1] = camera.pivot[1]; prevPivot[2] = camera.pivot[2]
        return moved
    }

    private fun aspect(): Float = surfaceWidth.toFloat() / surfaceHeight.toFloat()

    // ====== Gesture entry points (called on the GL thread via queueEvent) ======

    fun camOrbit(dxPx: Float, dyPx: Float) = camera.orbit(dxPx, dyPx)
    fun camDolly(spreadDeltaPx: Float) = camera.dolly(spreadDeltaPx)
    fun camPan(dxPx: Float, dyPx: Float) = camera.pan(dxPx, dyPx, surfaceHeight)

    /** Frame the whole cloud. Recomputes bounds from the live preview when streaming. */
    fun frameAll() {
        val source = previewSource
        if (source != null) {
            if (computeBounds(source.buffer, source.pointCount)) applyBoundsToCamera()
        }
        if (hasBounds || source != null) camera.frame(aspect(), animate = true)
    }

    /** Double-tap-then-drag → slide the pivot along the world-up (Z) axis, live. */
    fun zSlideMove(dyPx: Float) = camera.moveVertical(dyPx, surfaceHeight)

    /** Force the axis ruler on (true) or back to auto-show-while-moving (false). */
    fun setHelpersAlways(on: Boolean) { showHelpersAlways = on }

    fun setOrthographic(on: Boolean) { camera.orthographic = on }

    private fun drawRuler() {
        val count = AxisRuler.build(rulerBuffer, camera.pivot, camera.camDist())
        if (count == 0) return
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, projectionMatrix, 0, viewMatrix, 0)
        val posHandle = GLES20.glGetAttribLocation(program, "a_Position")
        val colorHandle = GLES20.glGetUniformLocation(program, "u_Color")
        val mvpHandle = GLES20.glGetUniformLocation(program, "u_MVPMatrix")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        GLES20.glUniform4f(colorHandle, 1f, 0.92f, 0.2f, 1f) // yellow
        GLES20.glEnableVertexAttribArray(posHandle)
        rulerBuffer.position(0)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, rulerBuffer)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, count)
        GLES20.glDisableVertexAttribArray(posHandle)
    }

    // ====== Bounds ======

    /** Track min/max while streaming xyz into [out]; returns false if empty. */
    private fun computeBounds(buffer: FloatBuffer, count: Int): Boolean {
        if (count <= 0) return false
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until count) {
            val o = i * 3
            val x = buffer.get(o); val y = buffer.get(o + 1); val z = buffer.get(o + 2)
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        }
        boundsMin[0] = minX; boundsMin[1] = minY; boundsMin[2] = minZ
        boundsMax[0] = maxX; boundsMax[1] = maxY; boundsMax[2] = maxZ
        hasBounds = true
        return true
    }

    private fun applyBoundsToCamera() {
        val cx = (boundsMin[0] + boundsMax[0]) * 0.5f
        val cy = (boundsMin[1] + boundsMax[1]) * 0.5f
        val cz = (boundsMin[2] + boundsMax[2]) * 0.5f
        val rx = (boundsMax[0] - boundsMin[0]) * 0.5f
        val ry = (boundsMax[1] - boundsMin[1]) * 0.5f
        val rz = (boundsMax[2] - boundsMin[2]) * 0.5f
        val radius = sqrt(rx * rx + ry * ry + rz * rz).coerceAtLeast(0.5f)
        camera.setScene(cx, cy, cz, radius)
    }

    // ====== Shaders / parsing (unchanged pipeline) ======

    private fun readShader(name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    private fun loadShader(type: Int, code: String): Int =
        GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, code)
            GLES20.glCompileShader(it)
        }

    private fun parsePCD(context: Context): Pair<FloatBuffer, Int> {
        val reader = BufferedReader(InputStreamReader(openScanFile(context)))
        val headerLines = mutableListOf<String>()
        var line: String?
        while (true) {
            line = reader.readLine() ?: break
            headerLines.add(line)
            if (line.startsWith("DATA")) break
        }
        reader.close()

        val pointCount = headerLines.firstOrNull { it.startsWith("POINTS") }
            ?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: 0

        val binaryStream = openScanFile(context)
        val skip = headerLines.joinToString("\n").toByteArray().size + 1
        binaryStream.skip(skip.toLong())

        val byteArray = ByteArray(pointCount * 8 * 4)
        binaryStream.read(byteArray)
        binaryStream.close()

        val sourceBuffer = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()

        val floatBuffer = ByteBuffer.allocateDirect(pointCount * 3 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until pointCount) {
            val x = sourceBuffer.get(i * 8)
            val y = sourceBuffer.get(i * 8 + 1)
            val z = sourceBuffer.get(i * 8 + 2)
            floatBuffer.put(x); floatBuffer.put(y); floatBuffer.put(z)
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        }
        floatBuffer.flip()

        if (pointCount > 0) {
            boundsMin[0] = minX; boundsMin[1] = minY; boundsMin[2] = minZ
            boundsMax[0] = maxX; boundsMax[1] = maxY; boundsMax[2] = maxZ
            hasBounds = true
        }

        return floatBuffer to pointCount
    }

    // ====== Circle drawing ======

    /** Draw the three reference circles centered at [center], uniformly scaled by [scale]. */
    private fun drawMarkerCircles(
        center: FloatArray, scale: Float,
        colXY: FloatArray, colYZ: FloatArray, colZX: FloatArray
    ) {
        Matrix.setIdentityM(circleModelMatrix, 0)
        Matrix.translateM(circleModelMatrix, 0, center[0], center[1], center[2])
        Matrix.scaleM(circleModelMatrix, 0, scale, scale, scale)
        drawCircle(circleXY, colXY, circleModelMatrix)
        drawCircle(circleYZ, colYZ, circleModelMatrix)
        drawCircle(circleZX, colZX, circleModelMatrix)
    }

    private fun drawCircle(buffer: FloatBuffer, color: FloatArray, modelMatrix: FloatArray) {
        val mvpMatrix = FloatArray(16)
        val tempMatrix = FloatArray(16)
        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)

        val positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
        val colorHandle = GLES20.glGetUniformLocation(program, "u_Color")
        val mvpHandle = GLES20.glGetUniformLocation(program, "u_MVPMatrix")

        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        buffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, buffer)
        GLES20.glUniform4fv(colorHandle, 1, color, 0)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, circleSegmentCount + 1)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    fun getPointCount(): Int = pointCount

    private fun openScanFile(context: Context): java.io.InputStream {
        val file = File(LocalScanStorage.scansDir(context), fileName)
        if (!file.exists()) {
            throw FileNotFoundException("File not found: ${file.absolutePath}")
        }
        return FileInputStream(file)
    }
}
