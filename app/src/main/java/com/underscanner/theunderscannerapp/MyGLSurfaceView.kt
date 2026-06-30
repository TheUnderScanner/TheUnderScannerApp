package com.underscanner.theunderscannerapp

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Hosts the GL renderer and translates touch into orbit-camera gestures:
 *
 *  - one-finger drag        → orbit (yaw/pitch)
 *  - two-finger pinch       → dolly (distance), and two-finger drag → pan, simultaneously
 *  - double-tap-then-drag   → depth-resolved teleport (helper ray + sliding marker)
 *
 * All camera mutation is forwarded to the renderer on the GL thread via [queueEvent],
 * so there is no locking against the draw loop. Gestures are applied 1:1 (no smoothing).
 */
class MyGLSurfaceView(
    context: Context,
    fileName: String = "scan1.pcd",
    liveMode: Boolean = false
) : GLSurfaceView(context) {

    val renderer: MyGLRenderer

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val tapTimeout = ViewConfiguration.getTapTimeout().toLong()

    // One-finger drag tracking
    private var previousX = 0f
    private var previousY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false

    // Two-finger tracking
    private var prevSpread = 0f
    private var prevMidX = 0f
    private var prevMidY = 0f

    // Double-tap-then-drag → live Z-axis slide of the pivot
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var armedForZSlide = false

    init {
        setEGLContextClientVersion(2)
        renderer = MyGLRenderer(context, fileName, liveMode)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                downTime = event.eventTime
                previousX = event.x; previousY = event.y
                moved = false
                // Arm a Z-slide if this down quickly follows a completed tap nearby.
                armedForZSlide = (event.eventTime - lastTapTime) < doubleTapTimeout &&
                    dist(downX, downY, lastTapX, lastTapY) < touchSlop * 2
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger → two-finger gesture; abandon any pending Z-slide.
                armedForZSlide = false
                initTwoFinger(event)
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    moveTwoFinger(event)
                } else {
                    val x = event.getX(0); val y = event.getY(0)
                    if (abs(x - downX) > touchSlop || abs(y - downY) > touchSlop) moved = true

                    val dy = y - previousY
                    if (armedForZSlide) {
                        // Vertical drag slides the pivot along world Z, live.
                        if (moved) queueEvent { renderer.zSlideMove(dy) }
                    } else {
                        val dx = x - previousX
                        queueEvent { renderer.camOrbit(dx, dy) }
                    }
                    previousX = x; previousY = y
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // A finger lifted; rebase one-finger tracking on a remaining pointer to
                // avoid an orbit jump, and don't let the leftover drag count as a tap.
                val remaining = if (event.actionIndex == 0) 1 else 0
                if (remaining < event.pointerCount) {
                    previousX = event.getX(remaining)
                    previousY = event.getY(remaining)
                    downX = previousX; downY = previousY
                }
                moved = true
            }

            MotionEvent.ACTION_UP -> {
                when {
                    armedForZSlide -> {
                        // Whether or not it dragged, the double-tap sequence is consumed.
                        lastTapTime = 0
                    }
                    !moved && (event.eventTime - downTime) < tapTimeout -> {
                        // A clean tap: remember it so a following tap+drag can Z-slide.
                        lastTapTime = event.eventTime
                        lastTapX = downX; lastTapY = downY
                    }
                    else -> lastTapTime = 0
                }
                armedForZSlide = false
            }

            MotionEvent.ACTION_CANCEL -> {
                armedForZSlide = false
                lastTapTime = 0
            }
        }
        return true
    }

    private fun initTwoFinger(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val x1 = event.getX(0); val y1 = event.getY(0)
        val x2 = event.getX(1); val y2 = event.getY(1)
        prevSpread = dist(x1, y1, x2, y2)
        prevMidX = (x1 + x2) / 2f
        prevMidY = (y1 + y2) / 2f
    }

    private fun moveTwoFinger(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val x1 = event.getX(0); val y1 = event.getY(0)
        val x2 = event.getX(1); val y2 = event.getY(1)
        val spread = dist(x1, y1, x2, y2)
        val midX = (x1 + x2) / 2f
        val midY = (y1 + y2) / 2f

        val spreadDelta = spread - prevSpread     // > 0 when spreading → increase distance
        val panDx = midX - prevMidX
        val panDy = midY - prevMidY

        queueEvent {
            renderer.camDolly(spreadDelta)
            renderer.camPan(panDx, panDy)
        }

        prevSpread = spread
        prevMidX = midX
        prevMidY = midY
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1; val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    /** Frame the entire cloud (UI "show me everything" escape hatch). */
    fun frameAll() = queueEvent { renderer.frameAll() }

    /** Force the axis ruler always-on (true) or auto-show-while-moving (false). */
    fun setHelpersAlways(on: Boolean) = queueEvent { renderer.setHelpersAlways(on) }

    /** Switch between perspective (false) and orthographic (true) projection. */
    fun setOrthographic(on: Boolean) = queueEvent { renderer.setOrthographic(on) }

    fun getPointCount(): Int = renderer.getPointCount()
}
