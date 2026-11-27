package com.rafajames.controletv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var tv: SamsungTv
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val repeatKeys = setOf("KEY_VOLUP", "KEY_VOLDOWN", "KEY_UP", "KEY_DOWN", "KEY_LEFT", "KEY_RIGHT")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        tv = SamsungTv(this) { msg -> runOnUiThread { status.text = msg } }
        wire(findViewById(R.id.root))
        findViewById<View>(R.id.settings).setOnClickListener { showSettings() }
        findViewById<View>(R.id.power).setOnClickListener { buzz(it); tv.power() }
        if (tv.ip == null) scan()
    }

    override fun onStart() { super.onStart(); tv.connect() }
    override fun onStop() { super.onStop(); tv.disconnect() }

    /** Todo botão com android:tag="KEY_..." envia essa tecla. */
    private fun wire(v: View) {
        if (v is ViewGroup) for (i in 0 until v.childCount) wire(v.getChildAt(i))
        val key = v.tag as? String ?: return
        if (key in repeatKeys) holdToRepeat(v, key)
        else v.setOnClickListener { buzz(it); tv.key(key) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun holdToRepeat(v: View, key: String) {
        val repeat = object : Runnable {
            override fun run() { tv.key(key); handler.postDelayed(this, 120) }
        }
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true; buzz(view); tv.key(key)
                    handler.postDelayed(repeat, 450)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false; handler.removeCallbacks(repeat)
                }
            }
            true
        }
    }

    private fun buzz(v: View) = v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    private fun showSettings() {
        val input = EditText(this).apply {
            hint = "Ex.: 192.168.0.15"
            inputType = InputType.TYPE_CLASS_PHONE
            setText(tv.ip ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("IP da TV")
            .setMessage("Na TV: Configurações → Geral → Rede → Status da rede → Configurações de IP")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) { tv.setIp(ip); tv.connect() }
            }
            .setNeutralButton("Procurar TV") { _, _ -> scan() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun scan() {
        status.text = "Procurando a TV na rede Wi-Fi…"
        thread {
            val ip = tv.scan()
            runOnUiThread {
                if (ip != null) tv.connect()
                else status.text = "Nenhuma TV encontrada. Ligue a TV, confira o Wi-Fi e toque em ⚙"
            }
        }
    }
}
