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

    // Interleaved 16-byte-per-point vertex data (file mode): xyz float32 + reflectivity/tag/
    // line/reserved bytes — same layout as PreviewCloud, so the draw path is identical for
    // saved files and the live preview. [vertexPosView] is a float view over the same memory
    // used for the position attribute; [vertexByteBuffer] serves the packed byte attributes.
    private lateinit var vertexByteBuffer: ByteBuffer
    private lateinit var vertexPosView: FloatBuffer
    private var program = 0
    private var pointCount: Int = 0

    // ----------------------------
    // Coloring / filtering state (set from the UI via MyGLSurfaceView.queueEvent)
    // ----------------------------
    @Volatile private var colorMode = ColorMode.UNIFORM
    @Volatile private var reflLow = 0f   // intensity mode lower bound, normalized [0,1]
    @Volatile private var reflHigh = 1f  // intensity mode upper bound, normalized [0,1]
    @Volatile private var noiseFilter = NoiseFilter.OFF

    // ----------------------------
    // Live preview (Phase 2)
    // ----------------------------
    @Volatile
    var previewSource: PreviewCloud? = null

    // LiDAR path polyline (live-accumulated, or loaded from a saved scan's .traj sidecar).
    @Volatile
    var trajectorySource: Trajectory? = null

    @Volatile
    private var showTrajectory = false

    @Volatile
    private var poseMarker: FloatArray? = null

    fun setPoseMarker(x: Float, y: Float, z: Float) {
        poseMarker = floatArrayOf(x, y, z)
    }

    // ----------------------------
    // Camera
    // ----------------------------
    val camera = OrbitCamera()

    // Automatic "show" orbit: continuous yaw spin around the pivot when enabled.
    @Volatile
    private var autoOrbitOn = false
    @Volatile
    private var autoOrbitDegPerSec = 0f

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

    // Graduation-scale reporting: fire a callback (on the GL thread) whenever the ruler's
    // step changes (1 m → 10 m → 100 m …), so the UI can flash the current scale.
    @Volatile
    var onScaleChanged: ((Float) -> Unit)? = null
    private var lastReportedStep = -1f

    init {
        if (liveMode) {
            vertexByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
            vertexPosView = vertexByteBuffer.asFloatBuffer()
            pointCount = 0
            camera.distance = 12f
        } else {
            val parsed = parsePCD(context)
            vertexByteBuffer = parsed.first
            vertexPosView = vertexByteBuffer.asFloatBuffer()
            pointCount = parsed.second
            Log.d("PCD", "Loaded $pointCount points from $fileName")
            if (hasBounds) {
                applyBoundsToCamera()
                pendingInitialFit = true // fit once the surface (aspect) is known
            }
            // Load the sidecar trajectory (.traj) for this scan, if it was downloaded.
            val trajFile = File(
                LocalScanStorage.scansDir(context),
                fileName.removeSuffix(".pcd") + ".traj"
            )
            Trajectory().takeIf { it.loadFile(trajFile) > 0 }?.let { trajectorySource = it }
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
        if (autoOrbitOn) camera.autoOrbit(autoOrbitDegPerSec, dt)

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
        // Both sources are interleaved 16-byte records: position = 3 floats at offset 0,
        // packed attributes = 4 normalized bytes at offset 12, stride 16 (zero-copy).
        val source = previewSource
        val posBuf: FloatBuffer
        val attrBuf: ByteBuffer
        val drawCount: Int
        if (source != null) {
            posBuf = source.floatBuffer
            attrBuf = source.byteBuffer
            drawCount = source.pointCount
        } else {
            posBuf = vertexPosView
            attrBuf = vertexByteBuffer
            drawCount = pointCount
        }

        // Coloring / filtering uniforms (mapping happens in the shader from the packed attribs).
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "u_ColorMode"), colorMode.ordinal)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "u_NoiseFilter"), noiseFilter.ordinal)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "u_Color"), 0.85f, 0.9f, 1f, 1f)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "u_ReflBounds"), reflLow, reflHigh)
        val sensor = poseMarker ?: boundsCenter(source)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "u_Sensor"), sensor[0], sensor[1], sensor[2])
        val hr = heightRange(source)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "u_HeightRange"), hr[0], hr[1])
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "u_DistScale"), distScale(source))

        val posHandle = GLES20.glGetAttribLocation(program, "a_Position")
        val attrHandle = GLES20.glGetAttribLocation(program, "a_Attribs")
        GLES20.glEnableVertexAttribArray(posHandle)
        posBuf.position(0)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, PreviewCloud.STRIDE, posBuf)
        if (attrHandle >= 0) {
            GLES20.glEnableVertexAttribArray(attrHandle)
            attrBuf.position(12)
            GLES20.glVertexAttribPointer(
                attrHandle, 4, GLES20.GL_UNSIGNED_BYTE, true, PreviewCloud.STRIDE, attrBuf
            )
        }
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, drawCount)
        GLES20.glDisableVertexAttribArray(posHandle)
        if (attrHandle >= 0) GLES20.glDisableVertexAttribArray(attrHandle)

        // --- Reference circles at the pivot (scaled to stay visible across the range) ---
        val pivotScale = camera.camDist() * 0.07f
        drawMarkerCircles(camera.pivot, pivotScale,
            floatArrayOf(1f, 0f, 0f, 1f), floatArrayOf(0f, 1f, 0f, 1f), floatArrayOf(0f, 0f, 1f, 1f))

        // --- LiDAR path polyline (yellow), when enabled ---
        if (showTrajectory) drawTrajectory()

        // --- Live preview: current sensor pose marker (amber) ---
        poseMarker?.let { p ->
            val amber = floatArrayOf(1f, 0.85f, 0f, 1f)
            drawMarkerCircles(p, camera.camDist() * 0.05f, amber, amber, amber)
        }

        // --- Axis ruler: show whenever the pivot is moving (or always, if toggled). ---
        // Driven purely by detecting pivot motion, so any code that moves the pivot lights it up.
        if (pivotMoved()) pivotMovingUntilMs = now + HELPER_FADE_MS
        if (showHelpersAlways || now < pivotMovingUntilMs) drawRuler()

        // --- Report graduation-scale changes (drives the top-left "échelle" flash). ---
        val step = AxisRuler.stepFor(camera.camDist())
        if (step != lastReportedStep) {
            val firstFrame = lastReportedStep < 0f
            lastReportedStep = step
            if (!firstFrame) onScaleChanged?.invoke(step)
        }
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

    /** Frame the whole cloud. Pulls fresh bounds from the live preview when streaming. */
    fun frameAll() {
        val source = previewSource
        if (source != null && source.pointCount > 0) {
            boundsMin[0] = source.minX; boundsMin[1] = source.minY; boundsMin[2] = source.minZ
            boundsMax[0] = source.maxX; boundsMax[1] = source.maxY; boundsMax[2] = source.maxZ
            hasBounds = true
            applyBoundsToCamera()
        }
        if (hasBounds) camera.frame(aspect(), animate = true)
    }

    // ====== Coloring / filtering (called on the GL thread via queueEvent) ======

    fun setColorMode(mode: ColorMode) { colorMode = mode }

    /** Intensity-mode reflectivity window, low/high normalized to [0,1]. */
    fun setReflBounds(low: Float, high: Float) {
        reflLow = low.coerceIn(0f, 1f)
        reflHigh = high.coerceIn(0f, 1f)
    }

    fun setNoiseFilter(level: NoiseFilter) { noiseFilter = level }

    /** Show/hide the LiDAR path polyline. */
    fun setShowTrajectory(on: Boolean) { showTrajectory = on }

    /** True once a path with at least a couple of points is available (live or loaded). */
    fun hasTrajectory(): Boolean = (trajectorySource?.pointCount ?: 0) >= 2

    /** Draw the accumulated LiDAR path as a yellow line strip (uniform-color path, no discard). */
    private fun drawTrajectory() {
        val traj = trajectorySource ?: return
        val count = traj.pointCount
        if (count < 2) return
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, projectionMatrix, 0, viewMatrix, 0)
        val posHandle = GLES20.glGetAttribLocation(program, "a_Position")
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "u_MVPMatrix"), 1, false, mvp, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "u_ColorMode"), 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "u_NoiseFilter"), 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "u_Color"), 1f, 0.85f, 0.1f, 1f)
        GLES20.glLineWidth(3f)
        GLES20.glEnableVertexAttribArray(posHandle)
        traj.buffer.position(0)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, traj.buffer)
        GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, count)
        GLES20.glDisableVertexAttribArray(posHandle)
    }

    // Scratch outputs reused each frame to avoid per-frame allocation.
    private val tmpCenter = FloatArray(3)
    private val tmpHeight = FloatArray(2)

    /** Center of the active cloud's bounds — the fallback "sensor" for distance mode with no pose. */
    private fun boundsCenter(source: PreviewCloud?): FloatArray {
        if (source != null && source.pointCount > 0) {
            tmpCenter[0] = (source.minX + source.maxX) * 0.5f
            tmpCenter[1] = (source.minY + source.maxY) * 0.5f
            tmpCenter[2] = (source.minZ + source.maxZ) * 0.5f
        } else {
            tmpCenter[0] = (boundsMin[0] + boundsMax[0]) * 0.5f
            tmpCenter[1] = (boundsMin[1] + boundsMax[1]) * 0.5f
            tmpCenter[2] = (boundsMin[2] + boundsMax[2]) * 0.5f
        }
        return tmpCenter
    }

    /** (minZ, maxZ) of the active cloud for the height colormap; never a zero-width range. */
    private fun heightRange(source: PreviewCloud?): FloatArray {
        var lo: Float; var hi: Float
        if (source != null && source.pointCount > 0) {
            lo = source.minZ; hi = source.maxZ
        } else {
            lo = boundsMin[2]; hi = boundsMax[2]
        }
        if (hi - lo < 1e-3f) hi = lo + 1f
        tmpHeight[0] = lo; tmpHeight[1] = hi
        return tmpHeight
    }

    /** Bounds diagonal, used to normalize the distance colormap. */
    private fun distScale(source: PreviewCloud?): Float {
        val dx: Float; val dy: Float; val dz: Float
        if (source != null && source.pointCount > 0) {
            dx = source.maxX - source.minX; dy = source.maxY - source.minY; dz = source.maxZ - source.minZ
        } else {
            dx = boundsMax[0] - boundsMin[0]; dy = boundsMax[1] - boundsMin[1]; dz = boundsMax[2] - boundsMin[2]
        }
        val diag = sqrt(dx * dx + dy * dy + dz * dz)
        return if (diag > 0.5f) diag else 12f
    }

    /** Double-tap-then-drag → slide the pivot along the world-up (Z) axis, live. */
    fun zSlideMove(dyPx: Float) = camera.moveVertical(dyPx, surfaceHeight)

    /** Force the axis ruler on (true) or back to auto-show-while-moving (false). */
    fun setHelpersAlways(on: Boolean) { showHelpersAlways = on }

    fun setOrthographic(on: Boolean) { camera.orthographic = on }

    /** Enable/disable the automatic "show" orbit (continuous yaw spin around the pivot). */
    fun setAutoOrbit(on: Boolean) { autoOrbitOn = on }

    /** Set the automatic orbit speed in degrees per second (sign = direction). */
    fun setAutoOrbitSpeed(degPerSec: Float) { autoOrbitDegPerSec = degPerSec }

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
        // Draw the overlay with the plain uniform-color path (no colormap / no discard).
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "u_ColorMode"), 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "u_NoiseFilter"), 0)
        GLES20.glEnableVertexAttribArray(posHandle)
        rulerBuffer.position(0)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, rulerBuffer)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, count)
        GLES20.glDisableVertexAttribArray(posHandle)
    }

    // ====== Bounds ======

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

    // ====== Shaders / parsing ======

    private fun readShader(name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    private fun loadShader(type: Int, code: String): Int =
        GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, code)
            GLES20.glCompileShader(it)
        }

    /**
     * Parse a binary `.pcd` (8 float32/point on disk) into an interleaved 16-byte-per-point
     * buffer matching the live layout: xyz float32 + reflectivity/tag/line/reserved bytes.
     * The 4th on-disk float is the Livox intensity (0–255) → the reflectivity byte, so the
     * saved-file viewer gets Intensity/Height/Distance coloring for free; tag/line are 0
     * (not present on disk), which the Tag mode / noise filter treat as "normal".
     */
    private fun parsePCD(context: Context): Pair<ByteBuffer, Int> {
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

        val interleaved = ByteBuffer.allocateDirect(pointCount * PreviewCloud.STRIDE)
            .order(ByteOrder.nativeOrder())

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until pointCount) {
            val x = sourceBuffer.get(i * 8)
            val y = sourceBuffer.get(i * 8 + 1)
            val z = sourceBuffer.get(i * 8 + 2)
            val intensity = sourceBuffer.get(i * 8 + 3)
            val o = i * PreviewCloud.STRIDE
            interleaved.putFloat(o, x)
            interleaved.putFloat(o + 4, y)
            interleaved.putFloat(o + 8, z)
            interleaved.put(o + 12, intensity.coerceIn(0f, 255f).toInt().toByte()) // reflectivity
            interleaved.put(o + 13, 0)  // tag (absent on disk)
            interleaved.put(o + 14, 0)  // line (absent on disk)
            interleaved.put(o + 15, 0)  // reserved
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        }
        interleaved.position(0)

        if (pointCount > 0) {
            boundsMin[0] = minX; boundsMin[1] = minY; boundsMin[2] = minZ
            boundsMax[0] = maxX; boundsMax[1] = maxY; boundsMax[2] = maxZ
            hasBounds = true
        }

        return interleaved to pointCount
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
        // Marker circles use the plain uniform-color path (no colormap / no discard).
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "u_ColorMode"), 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "u_NoiseFilter"), 0)
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
