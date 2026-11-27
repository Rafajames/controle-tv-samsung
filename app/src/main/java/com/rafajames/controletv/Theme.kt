package com.rafajames.controletv

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

/** Temas do controle. "Cristal" = vidro translúcido sobre fundo com reflexos coloridos. */
data class Palette(
    val name: String,
    val dark: Boolean,
    val base: Int,
    val blobs: List<Triple<Float, Float, Int>>,   // centro x, centro y (0..1) e cor de cada reflexo
    val panel: IntArray, val btn: IntArray, val ok: IntArray, val ring: IntArray,
    val edge: Int, val text: Int, val muted: Int, val arrow: Int,
    val sheet: Int, val raised: Boolean,           // raised = sombra nos botões (fundo opaco)
    val accents: IntArray,
)

object Themes {
    val cristal = Palette("Cristal", true, 0xFF0B0F1A.toInt(),
        listOf(Triple(0.1f, 0f, 0xFF2B3F6B.toInt()), Triple(1f, 0.45f, 0xFF3B2A63.toInt()), Triple(0.2f, 1f, 0xFF134E5E.toInt())),
        panel = c(0x24FFFFFF, 0x0AFFFFFF), btn = c(0x33FFFFFF, 0x0FFFFFFF), ok = c(0x47FFFFFF, 0x14FFFFFF), ring = c(0x38FFFFFF, 0x0DFFFFFF),
        edge = 0x38FFFFFF, text = 0xFFF2F6FF.toInt(), muted = 0xFFB9C3D6.toInt(), arrow = 0xB3FFFFFF.toInt(),
        sheet = 0xF2161B29.toInt(), raised = false,
        accents = c(0xFF7CC4FF.toInt(), 0xFFB49CFF.toInt(), 0xFF5EEAD4.toInt(), 0xFFFFB86B.toInt()))

    val branco = Palette("Cristal branco", false, 0xFFF3F6FA.toInt(),
        listOf(Triple(0f, 0f, 0xFFFFFFFF.toInt()), Triple(1f, 0.4f, 0xFFE4EAF3.toInt()), Triple(0.3f, 1f, 0xFFDCE4EE.toInt())),
        panel = c(0xD9FFFFFF.toInt(), 0x59FFFFFF), btn = c(0xFFFFFFFF.toInt(), 0xFFF2F5FA.toInt()), ok = c(0xFFFFFFFF.toInt(), 0xFFEEF2F7.toInt()),
        ring = c(0xFFFFFFFF.toInt(), 0x8CFFFFFF.toInt()),
        edge = 0x3396A5BE, text = 0xFF141A26.toInt(), muted = 0xFF4B5566.toInt(), arrow = 0xFF6B7586.toInt(),
        sheet = 0xF7F7F9FC.toInt(), raised = true,
        accents = c(0xFF2563EB.toInt(), 0xFF7C5CFF.toInt(), 0xFF0E8F8A.toInt(), 0xFFB8650A.toInt()))

    val claro = Palette("Cristal claro", false, 0xFFEEF2F8.toInt(),
        listOf(Triple(0.1f, 0f, 0xFFCFE3FF.toInt()), Triple(1f, 0.45f, 0xFFE6DAFF.toInt()), Triple(0.2f, 1f, 0xFFCFF5EE.toInt())),
        panel = c(0xBFFFFFFF.toInt(), 0x66FFFFFF), btn = c(0xF2FFFFFF.toInt(), 0x8CFFFFFF.toInt()), ok = c(0xFFFFFFFF.toInt(), 0xB3FFFFFF.toInt()),
        ring = c(0xF2FFFFFF.toInt(), 0x73FFFFFF),
        edge = 0xE6FFFFFF.toInt(), text = 0xFF1A2233.toInt(), muted = 0xFF4A5568.toInt(), arrow = 0xFF5B6678.toInt(),
        sheet = 0xF7F4F7FC.toInt(), raised = false,
        accents = c(0xFF2563EB.toInt(), 0xFF7C5CFF.toInt(), 0xFF0E8F8A.toInt(), 0xFFB8650A.toInt()))

    val grafite = Palette("Grafite", true, 0xFF15171B.toInt(), emptyList(),
        panel = c(0xFF1C1F24.toInt(), 0xFF1C1F24.toInt()), btn = c(0xFF24272D.toInt(), 0xFF24272D.toInt()),
        ok = c(0xFF16181C.toInt(), 0xFF16181C.toInt()), ring = c(0xFF2A2E35.toInt(), 0xFF1E2126.toInt()),
        edge = 0xFF2E323A.toInt(), text = 0xFFE8ECF2.toInt(), muted = 0xFFAEB8C4.toInt(), arrow = 0xFF8C95A3.toInt(),
        sheet = 0xFF1C1F24.toInt(), raised = true,
        accents = c(0xFF4C9BFF.toInt(), 0xFFB49CFF.toInt(), 0xFF5EEAD4.toInt(), 0xFFFFB86B.toInt()))

    val all = listOf(cristal, branco, claro, grafite)
    val accentNames = listOf("Azul", "Lilás", "Verde-água", "Âmbar")

    fun byName(n: String?) = all.firstOrNull { it.name == n } ?: branco

    private fun c(vararg v: Int) = v

    /** Vidro: degradê translúcido + contorno fino. radius < 0 = círculo. */
    fun glass(colors: IntArray, edge: Int, radius: Float, strokePx: Int) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, colors).apply {
        if (radius < 0) shape = GradientDrawable.OVAL else cornerRadius = radius
        setStroke(strokePx, edge)
    }

    /** Vidro que reage ao toque. */
    fun pressable(p: Palette, colors: IntArray, radius: Float, strokePx: Int): Drawable {
        val ripple = if (p.dark) 0x33FFFFFF else 0x22000000
        return RippleDrawable(ColorStateList.valueOf(ripple), glass(colors, p.edge, radius, strokePx), null)
    }

    fun solid(color: Int, radius: Float) = GradientDrawable().apply { cornerRadius = radius; setColor(color) }
}

/** Fundo da tela: cor base com reflexos suaves (os "cristais" aparecem por cima). */
class CrystalBackground(private val p: Palette) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawColor(p.base)
        val r = maxOf(b.width(), b.height()) * 0.55f
        p.blobs.forEach { (x, y, color) ->
            val cx = b.left + x * b.width(); val cy = b.top + y * b.height()
            paint.shader = RadialGradient(cx, cy, r, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, paint)
        }
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
