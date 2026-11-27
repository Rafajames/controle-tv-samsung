package com.rafajames.controletv

import android.content.Context
import android.hardware.ConsumerIrManager
import java.util.concurrent.Executors

/** Controle infravermelho: protocolo Samsung (endereço 0x07) ou LG (NEC, endereço 0x04). */
class IrRemote(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)
    /** "samsung" ou "lg" (Configurações › Trocar › Infravermelho). */
    var brand: String
        get() = prefs.getString("irBrand", "samsung") ?: "samsung"
        set(v) { prefs.edit().putString("irBrand", v).apply() }
    private val ir = ctx.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
    private val worker = Executors.newSingleThreadExecutor()
    val available: Boolean get() = ir?.hasIrEmitter() == true

    private val codes = mapOf(
        "KEY_POWER" to 0x02, "KEY_SOURCE" to 0x01, "KEY_MENU" to 0x1A,
        "KEY_VOLUP" to 0x07, "KEY_VOLDOWN" to 0x0B, "KEY_MUTE" to 0x0F,
        "KEY_CHUP" to 0x12, "KEY_CHDOWN" to 0x10,
        "KEY_UP" to 0x60, "KEY_DOWN" to 0x61, "KEY_LEFT" to 0x65, "KEY_RIGHT" to 0x62,
        "KEY_ENTER" to 0x68, "KEY_RETURN" to 0x58, "KEY_HOME" to 0x79,
        "KEY_1" to 0x04, "KEY_2" to 0x05, "KEY_3" to 0x06, "KEY_4" to 0x08, "KEY_5" to 0x09,
        "KEY_6" to 0x0A, "KEY_7" to 0x0C, "KEY_8" to 0x0D, "KEY_9" to 0x0E, "KEY_0" to 0x11,
        "KEY_PLAY" to 0x47, "KEY_PAUSE" to 0x4A, "KEY_STOP" to 0x46, "KEY_REWIND" to 0x45, "KEY_FF" to 0x48,
    )

    /** Códigos LG como aparecem nas tabelas (20DF xx..): o byte é invertido ao transmitir. */
    private val lg = mapOf(
        "KEY_POWER" to 0x10, "KEY_SOURCE" to 0xD0, "KEY_MENU" to 0xC2,
        "KEY_VOLUP" to 0x40, "KEY_VOLDOWN" to 0xC0, "KEY_MUTE" to 0x90,
        "KEY_CHUP" to 0x00, "KEY_CHDOWN" to 0x80,
        "KEY_UP" to 0x02, "KEY_DOWN" to 0x82, "KEY_LEFT" to 0xE0, "KEY_RIGHT" to 0x60,
        "KEY_ENTER" to 0x22, "KEY_RETURN" to 0x14, "KEY_HOME" to 0x3E,
        "KEY_1" to 0x88, "KEY_2" to 0x48, "KEY_3" to 0xC8, "KEY_4" to 0x28, "KEY_5" to 0xA8,
        "KEY_6" to 0x68, "KEY_7" to 0xE8, "KEY_8" to 0x18, "KEY_9" to 0x98, "KEY_0" to 0x08,
        "KEY_PLAY" to 0x0D, "KEY_PAUSE" to 0x5D, "KEY_STOP" to 0x8D, "KEY_REWIND" to 0xF1, "KEY_FF" to 0x71,
    )

    fun send(key: String) {
        val m = ir ?: return
        val p = if (brand == "lg") lg[key]?.let { pattern(0x04, 0xFB, rev(it), 9000) }
            else codes[key]?.let { pattern(0x07, 0x07, it, 4500) }
        p ?: return
        worker.execute { runCatching { m.transmit(38000, p) } }
    }

    private fun rev(b: Int) = Integer.reverse(b) ushr 24 and 0xFF

    private fun pattern(a1: Int, a2: Int, cmd: Int, lead: Int): IntArray {
        val p = mutableListOf(lead, 4500)
        for (byte in intArrayOf(a1, a2, cmd, cmd.inv() and 0xFF))
            for (bit in 0 until 8) { p += 560; p += if ((byte shr bit) and 1 == 1) 1690 else 560 }
        p += 560
        return p.toIntArray()
    }
}
