package com.underscanner.theunderscannerapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ControlMode {
    TOUCH,      // Original touch controls
    JOYSTICK,   // Virtual joystick controls (FPV only)
    SPLIT       // Two-finger split control
}

class CameraController(
    private val glView: MyGLSurfaceView
) {
    private var _controlMode by mutableStateOf(ControlMode.TOUCH)
    var controlMode: ControlMode
        get() = _controlMode
        set(value) {
            _controlMode = value
            // Sync control mode to GLView
            glView.controlMode = value
        }

    private var _autoLevel by mutableStateOf(false)
    var autoLevel: Boolean
        get() = _autoLevel
        set(value) {
            _autoLevel = value
            // Sync auto-level to renderer
            glView.renderer.autoLevelEnabled = value
        }

    // Joystick input state
    private var movementX = 0f
    private var movementY = 0f
    private var lookX = 0f
    private var lookY = 0f

    /**
     * Update movement joystick (left stick)
     */
    fun updateMovementJoystick(x: Float, y: Float) {
        movementX = x
        movementY = y
    }

    /**
     * Update look joystick (right stick)
     */
    fun updateLookJoystick(x: Float, y: Float) {
        lookX = x
        lookY = y
    }

    /**
     * Apply joystick input to camera
     * Should be called every frame
     */
    fun applyJoystickInput() {
        if (_controlMode != ControlMode.JOYSTICK) return

        val renderer = glView.renderer

        when (renderer.cameraMode) {
            CameraMode.FPV -> {
                // Look controls (right stick)
                if (lookX != 0f || lookY != 0f) {
                    val lookSpeed = 2f
                    // Natural FPS-style controls: right=look right, up=look up
                    renderer.rotateFPV(lookX * lookSpeed, -lookY * lookSpeed)
                }

                // Movement controls (left stick)
                if (movementX != 0f || movementY != 0f) {
                    val moveSpeed = 0.3f
                    // Forward/back with Y (up=forward, down=backward), strafe with X (inverted)
                    renderer.moveFPV(movementY * moveSpeed, -movementX * moveSpeed, 0f)
                }
            }
            CameraMode.ORBIT -> {
                // Orbital camera with joysticks
                if (lookX != 0f || lookY != 0f) {
                    val rotateSpeed = 2f
                    // Invert both axes for natural rotation controls
                    renderer.rotateOrbit(-lookX * rotateSpeed, lookY * rotateSpeed)
                }
            }
        }
    }
}
