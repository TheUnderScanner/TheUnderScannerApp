package com.underscanner.theunderscannerapp


import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent



class MyGLSurfaceView(
    context: Context,
    fileName: String = "scan1.pcd",
    liveMode: Boolean = false
) : GLSurfaceView(context) {

    // Renderer responsible for OpenGL drawing
    val renderer: MyGLRenderer

    // Control mode - can be changed externally
    var controlMode: ControlMode = ControlMode.TOUCH

    // Variables for touch handling
    private var prevTouchDistance = 0f
    private var prevMidX = 0f
    private var prevMidY = 0f
    private var mode = 0 // 0 = none, 1 = one finger, 2 = two fingers
    private var previousX = 0f
    private var previousY = 0f

    // Split control tracking
    private var leftTouchId = -1
    private var rightTouchId = -1
    private var leftPrevX = 0f
    private var leftPrevY = 0f
    private var rightPrevX = 0f
    private var rightPrevY = 0f

    init {
        // Set OpenGL ES version
        setEGLContextClientVersion(2)

        // Create renderer and assign it
        renderer = MyGLRenderer(context, fileName, liveMode)
        setRenderer(renderer)

        // Render continuously (or RENDERMODE_WHEN_DIRTY for manual updates)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // If joystick mode is active, don't process touch events here
        if (controlMode == ControlMode.JOYSTICK) {
            return false
        }

        // Handle split control mode differently
        if (controlMode == ControlMode.SPLIT) {
            return handleSplitControlTouch(event)
        }

        // Standard touch mode handling
        // Reset previous values to avoid jumps on finger lift
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            prevTouchDistance = 0f
            prevMidX = 0f
            prevMidY = 0f
        }

        // Handle different touch scenarios
        when (event.pointerCount) {
            1 -> handleSingleTouch(event) // Single finger: orbit rotation
            2 -> handleMultiTouch(event) // Two fingers: zoom and pan
        }
        return true
    }

    // Handle single touch events (camera rotation).
    private fun handleSingleTouch(event: MotionEvent) {
        val x = event.getX(0)
        val y = event.getY(0)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val dx = x - previousX
                val dy = y - previousY

                when (renderer.cameraMode) {
                    CameraMode.ORBIT -> renderer.rotateOrbit(dx * 0.3f, dy * 0.3f)
                    CameraMode.FPV -> renderer.rotateFPV(dx * 0.3f, dy * 0.3f)
                }
            }
        }

        previousX = x
        previousY = y
    }

    // Handle multi-touch events (pinch zoom and two-finger pan)
    private fun handleMultiTouch(event: MotionEvent) {
        if (event.pointerCount < 2) return

        val x1 = event.getX(0)
        val y1 = event.getY(0)
        val x2 = event.getX(1)
        val y2 = event.getY(1)

        val midX = (x1 + x2) / 2f
        val midY = (y1 + y2) / 2f

        val dx = midX - prevMidX
        val dy = midY - prevMidY

        val newDist = distance(x1, y1, x2, y2)
        val distDelta = newDist - prevTouchDistance

        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            when (renderer.cameraMode) {
                CameraMode.ORBIT -> {
                    // Pinch Zoom
                    renderer.zoomOrbitCamera(distDelta * 0.01f)
                    // Two-finger Pan
                    renderer.panOrbitTarget(-dx, -dy)
                }
                CameraMode.FPV -> {
                    // Two-finger pan moves camera position in FPV mode
                    // Up/down movement on Y axis (vertical), left/right on XZ plane
                    val moveSpeed = 0.02f
                    renderer.moveFPV(dy * moveSpeed, -dx * moveSpeed, 0f)
                }
            }
        }

        // Update previous values
        prevTouchDistance = newDist
        prevMidX = midX
        prevMidY = midY
    }

    // Utility function to calculate the distance between two points (used for pinch detection)
    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    // Public method to expose the number of points in the point cloud
    fun getPointCount(): Int {
        return renderer.getPointCount()
    }

    // Public method to change camera mode
    fun setCameraMode(mode: CameraMode) {
        renderer.setCameraMode(mode)
    }

    // Public method to get current camera mode
    fun getCameraMode(): CameraMode {
        return renderer.cameraMode
    }

    // Handle split control touch events
    private fun handleSplitControlTouch(event: MotionEvent): Boolean {
        val screenWidth = width
        val screenMidX = screenWidth / 2f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Assign touch to left or right side
                for (i in 0 until event.pointerCount) {
                    val x = event.getX(i)
                    val id = event.getPointerId(i)

                    if (x < screenMidX && leftTouchId == -1) {
                        leftTouchId = id
                        leftPrevX = x
                        leftPrevY = event.getY(i)
                    } else if (x >= screenMidX && rightTouchId == -1) {
                        rightTouchId = id
                        rightPrevX = x
                        rightPrevY = event.getY(i)
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)

                    when (id) {
                        leftTouchId -> {
                            // Left side: movement
                            val dx = x - leftPrevX
                            val dy = y - leftPrevY
                            val moveSpeed = 0.02f

                            when (renderer.cameraMode) {
                                CameraMode.ORBIT -> renderer.panOrbitTarget(-dx, -dy)
                                CameraMode.FPV -> renderer.moveFPV(dy * moveSpeed, -dx * moveSpeed, 0f)
                            }

                            leftPrevX = x
                            leftPrevY = y
                        }
                        rightTouchId -> {
                            // Right side: look direction
                            val dx = x - rightPrevX
                            val dy = y - rightPrevY
                            val lookSpeed = 0.3f

                            when (renderer.cameraMode) {
                                CameraMode.ORBIT -> renderer.rotateOrbit(dx * lookSpeed, dy * lookSpeed)
                                CameraMode.FPV -> renderer.rotateFPV(dx * lookSpeed, dy * lookSpeed)
                            }

                            rightPrevX = x
                            rightPrevY = y
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = event.getPointerId(event.actionIndex)
                if (id == leftTouchId) {
                    leftTouchId = -1
                }
                if (id == rightTouchId) {
                    rightTouchId = -1
                }
            }
        }

        return true
    }

}
