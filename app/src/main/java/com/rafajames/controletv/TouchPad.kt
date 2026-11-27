package com.rafajames.controletv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Área de toque no lugar do direcional.
 * Navegação: deslizar = setas, tocar = OK. Mouse: arrastar = cursor, tocar = clique.
 * Segurar alterna entre os dois modos.
 */
class TouchPad(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {

    var mouse = false
        set(v) { field = v; invalidate() }
    var onArrow: ((String) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onMouse: ((Int, Int) -> Unit)? = null
    var onToggleMode: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val step = 48 * d
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val ui = Handler(Looper.getMainLooper())
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E222A.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF383E4A.toInt(); style = Paint.Style.STROKE; strokeWidth = d }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8ECF2.toInt(); textSize = 17 * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8A96A3.toInt(); textSize = 13 * d; textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastY = 0f
    private var accX = 0f; private var accY = 0f; private var moved = false; private var longDone = false
    private var mouseDx = 0f; private var mouseDy = 0f; private var lastSend = 0L
    private val longPress = Runnable { longDone = true; onToggleMode?.invoke() }

    override fun onDraw(c: Canvas) {
        rect.set(d, d, width - d, height - d)
        c.drawRoundRect(rect, 36 * d, 36 * d, bg)
        c.drawRoundRect(rect, 36 * d, 36 * d, border)
        val cx = width / 2f; val cy = height / 2f
        c.drawText(if (mouse) "Mouse" else "Touchpad", cx, cy - 18 * d, title)
        c.drawText(if (mouse) "Arraste = cursor · toque = clique" else "Deslize = setas · toque = OK", cx, cy + 8 * d, hint)
        c.drawText(if (mouse) "Segure para voltar às setas" else "Segure para modo mouse (navegador)", cx, cy + 30 * d, hint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                accX = 0f; accY = 0f; moved = false; longDone = false; mouseDx = 0f; mouseDy = 0f
                ui.postDelayed(longPress, 650)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX; val dy = e.y - lastY
                lastX = e.x; lastY = e.y
                if (!moved && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) { moved = true; ui.removeCallbacks(longPress) }
                if (!moved || longDone) return true
                if (mouse) {
                    mouseDx += dx * 1.6f; mouseDy += dy * 1.6f
                    val now = System.currentTimeMillis()
                    if (now - lastSend > 30) { flushMouse(); lastSend = now }
                } else {
                    accX += dx; accY += dy
                    // Cada "passo" de dedo vira uma seta (na direção dominante)
                    while (abs(accX) >= step || abs(accY) >= step) {
                        if (abs(accX) >= abs(accY)) {
                            onArrow?.invoke(if (accX > 0) "KEY_RIGHT" else "KEY_LEFT"); accX -= step * Math.signum(accX); accY = 0f
                        } else {
                            onArrow?.invoke(if (accY > 0) "KEY_DOWN" else "KEY_UP"); accY -= step * Math.signum(accY); accX = 0f
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                ui.removeCallbacks(longPress)
                if (mouse) flushMouse()
                if (!moved && !longDone && e.eventTime - e.downTime < 400) { performClick(); onTap?.invoke() }
            }
            MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(longPress)
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun flushMouse() {
        val x = mouseDx.toInt(); val y = mouseDy.toInt()
        if (x == 0 && y == 0) return
        mouseDx -= x; mouseDy -= y
        onMouse?.invoke(x, y)
    }
}
