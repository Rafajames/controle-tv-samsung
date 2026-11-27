package com.rafajames.controletv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.animation.AnimatorInflater
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.CheckBox
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var tv: SamsungTv
    private lateinit var status: TextView
    private lateinit var ir: IrRemote
    private lateinit var modeBtn: Button
    private lateinit var appsRow: LinearLayout
    private val prefs by lazy { getSharedPreferences("tv", MODE_PRIVATE) }
    private var irMode: Boolean
        get() = ir.available && prefs.getBoolean("ir", true)
        set(v) { prefs.edit().putBoolean("ir", v).apply() }
    private val handler = Handler(Looper.getMainLooper())
    private val repeatKeys = setOf("KEY_VOLUP", "KEY_VOLDOWN", "KEY_UP", "KEY_DOWN", "KEY_LEFT", "KEY_RIGHT")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        tv = SamsungTv(this) { msg -> runOnUiThread { status.text = msg } }
        ir = IrRemote(this)
        modeBtn = findViewById(R.id.mode)
        appsRow = findViewById(R.id.apps)
        wire(findViewById(R.id.root))
        findViewById<View>(R.id.settings).setOnClickListener { showSettings() }
        findViewById<View>(R.id.power).setOnClickListener { buzz(Buzz.HEAVY); if (irMode) ir.send("KEY_POWER") else tv.power() }
        val numpad = findViewById<View>(R.id.numpad)
        numpad.visibility = if (prefs.getBoolean("numpad", false)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.dialpad).setOnClickListener {
            buzz()
            val show = numpad.visibility != View.VISIBLE
            numpad.visibility = if (show) View.VISIBLE else View.GONE
            prefs.edit().putBoolean("numpad", show).apply()
            if (show) findViewById<ScrollView>(R.id.scroll).let { sv -> sv.post { sv.smoothScrollTo(0, numpad.bottom) } }
        }
        modeBtn.setOnClickListener {
            buzz()
            if (!ir.available) { status.text = "Este celular não tem infravermelho"; return@setOnClickListener }
            irMode = !irMode; applyMode()
        }
        findViewById<Button>(R.id.update).apply {
            text = "Verificar atualização (versão $myVersion)"
            setOnClickListener { checkUpdate() }
        }
        renderApps()
        applyMode()
        if (!irMode && tv.ip == null) scan()
    }

    private fun applyMode() {
        modeBtn.text = if (irMode) "IR" else "Wi-Fi"
        modeBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(if (irMode) R.drawable.ic_ir else R.drawable.ic_wifi, 0, 0, 0)
        if (irMode) { tv.disconnect(); status.text = "Infravermelho: aponte o celular para a TV" }
        else tv.connect()
    }

    private fun press(key: String) = if (irMode) ir.send(key) else tv.key(key)

    override fun onStart() { super.onStart(); if (!irMode) tv.connect() }
    override fun onStop() { super.onStop(); tv.disconnect() }

    /** Todo botão com android:tag="KEY_..." envia essa tecla. */
    private fun wire(v: View) {
        if (v is ViewGroup) for (i in 0 until v.childCount) wire(v.getChildAt(i))
        val key = v.tag as? String ?: return
        if (key in repeatKeys) holdToRepeat(v, key)
        else v.setOnClickListener { buzz(); press(key) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun holdToRepeat(v: View, key: String) {
        val repeat = object : Runnable {
            override fun run() { buzz(Buzz.TICK); press(key); handler.postDelayed(this, 120) }
        }
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true; buzz(); press(key)
                    handler.postDelayed(repeat, 450)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false; handler.removeCallbacks(repeat)
                }
            }
            true
        }
    }

    // ---------- Vibração ----------
    // Usa o motor de vibração direto (não depende da "vibração ao toque" do sistema).

    private enum class Buzz { CLICK, TICK, HEAVY }
    private val vibrator by lazy { getSystemService(Vibrator::class.java) }
    private var hapticOn: Boolean
        get() = prefs.getBoolean("haptic", true)
        set(v) { prefs.edit().putBoolean("haptic", v).apply() }

    private fun buzz(kind: Buzz = Buzz.CLICK) {
        val vib = vibrator ?: return
        if (!hapticOn || !vib.hasVibrator()) return
        val effect = if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(when (kind) {
            Buzz.CLICK -> VibrationEffect.EFFECT_CLICK
            Buzz.TICK -> VibrationEffect.EFFECT_TICK
            Buzz.HEAVY -> VibrationEffect.EFFECT_HEAVY_CLICK
        }) else VibrationEffect.createOneShot(when (kind) { Buzz.CLICK -> 18L; Buzz.TICK -> 8L; Buzz.HEAVY -> 45L },
            VibrationEffect.DEFAULT_AMPLITUDE)
        vib.vibrate(effect)
    }

    private fun showSettings() {
        val input = EditText(this).apply {
            hint = "Ex.: 192.168.0.15"
            inputType = InputType.TYPE_CLASS_PHONE
            setText(tv.ip ?: "")
        }
        val vibrate = CheckBox(this).apply { text = "Vibrar ao tocar nos botões"; isChecked = hapticOn }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(input); addView(vibrate)
        }
        AlertDialog.Builder(this)
            .setTitle("Configurações")
            .setMessage("IP da TV — na TV: Configurações → Geral → Rede → Status da rede → Configurações de IP")
            .setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                hapticOn = vibrate.isChecked
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

    // ---------- Atalhos de apps ----------

    private val defaultApps = """[
        {"name":"Netflix","id":"3201907018807"},
        {"name":"Prime Video","id":"3201910019365"},
        {"name":"Disney+","id":"3201901017640"},
        {"name":"Crunchyroll","id":""},
        {"name":"Xbox","id":"3202203026799"}]"""

    private fun loadApps(): MutableList<Pair<String, String>> {
        val arr = JSONArray(prefs.getString("apps", null) ?: defaultApps)
        return (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.getString("name") to o.optString("id") } }
            .toMutableList()
    }

    private fun saveApps(list: List<Pair<String, String>>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.first).put("id", it.second)) }
        prefs.edit().putString("apps", arr.toString()).apply()
        renderApps()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun chip(text: String) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 15f
        background = getDrawable(R.drawable.btn)
        stateListAnimator = AnimatorInflater.loadStateListAnimator(context, R.animator.press)
        setPadding(dp(18), 0, dp(18), 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(52))
            .apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
    }

    private fun renderApps() {
        appsRow.removeAllViews()
        loadApps().forEachIndexed { i, (name, id) ->
            appsRow.addView(chip(name).apply {
                setOnClickListener { buzz(); tv.launch(name, id) }
                setOnLongClickListener { editApp(i); true }
            })
        }
        appsRow.addView(chip("＋").apply { setOnClickListener { buzz(); addApp() } })
    }

    private fun editApp(i: Int) {
        val list = loadApps()
        AlertDialog.Builder(this).setTitle(list[i].first)
            .setItems(arrayOf("Escolher o app da TV", "Remover atalho")) { _, which ->
                if (which == 0) pickFromTv { name, id -> list[i] = list[i].first to id; saveApps(list); toast("$name vinculado") }
                else { list.removeAt(i); saveApps(list) }
            }.show()
    }

    private fun addApp() = pickFromTv { name, id -> saveApps(loadApps().apply { add(name to id) }) }

    /** Mostra os apps instalados na TV; se a lista não vier, pede nome e ID manualmente. */
    private fun pickFromTv(done: (String, String) -> Unit) {
        val apps = tv.installed
        if (apps.isEmpty()) {
            if (tv.ip != null && !tv.connected) {
                tv.connect(); status.text = "Buscando apps da TV… tente de novo em alguns segundos"
            }
            manualApp(done); return
        }
        AlertDialog.Builder(this).setTitle("Apps da TV")
            .setItems(apps.map { it.first }.toTypedArray()) { _, w -> done(apps[w].first, apps[w].second) }
            .setNeutralButton("Digitar ID") { _, _ -> manualApp(done) }
            .show()
    }

    private fun manualApp(done: (String, String) -> Unit) {
        val name = EditText(this).apply { hint = "Nome (ex.: YouTube)" }
        val id = EditText(this).apply { hint = "ID do app na TV (opcional)" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(name); addView(id)
        }
        AlertDialog.Builder(this).setTitle("Novo atalho")
            .setMessage("Sem ID, o app procura pelo nome na TV (precisa do Wi-Fi).")
            .setView(box)
            .setPositiveButton("Adicionar") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isNotEmpty()) done(n, id.text.toString().trim())
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun toast(t: String) { status.text = t }

    // ---------- Atualização ----------

    private val myVersion: Long get() = packageManager.getPackageInfo(packageName, 0).longVersionCode

    private fun checkUpdate() {
        status.text = "Verificando atualização…"
        thread {
            val latest = runCatching {
                val c = URL("https://api.github.com/repos/Rafajames/controle-tv-samsung/releases/latest")
                    .openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    .getString("tag_name").removePrefix("v").toLong()
            }.getOrNull()
            runOnUiThread {
                when {
                    latest == null -> status.text = "Não consegui verificar. Confira a internet"
                    latest <= myVersion -> status.text = "Você já tem a versão mais nova ($myVersion)"
                    else -> AlertDialog.Builder(this)
                        .setTitle("Versão $latest disponível")
                        .setMessage("Você tem a versão $myVersion. Baixar e instalar por cima?")
                        .setPositiveButton("Baixar") { _, _ ->
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                                "https://github.com/Rafajames/controle-tv-samsung/releases/download/v$latest/controle-tv.apk")))
                        }
                        .setNegativeButton("Agora não", null).show()
                }
            }
        }
    }
}
