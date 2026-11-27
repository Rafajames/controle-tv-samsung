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
import android.app.PendingIntent
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.RadioButton
import android.widget.RadioGroup
import android.content.ActivityNotFoundException
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.DragEvent
import android.os.SystemClock
import android.widget.CheckBox
import android.widget.ImageButton
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import java.text.Normalizer
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
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private companion object {
        const val SPEECH_COMMAND = 1; const val SPEECH_TEXT = 2
        const val ACTION_INSTALLED = "com.rafajames.controletv.INSTALLED"
        /** Versões publicadas pelo GitHub Actions (o repositório precisa ser público). */
        const val RELEASES = "https://github.com/Rafajames/controle-tv-samsung/releases"
        const val RELEASES_API = "https://api.github.com/repos/Rafajames/controle-tv-samsung/releases/latest"
    }

    private lateinit var tv: SamsungTv
    private lateinit var status: Banner
    private lateinit var ir: IrRemote
    private lateinit var appsRow: LinearLayout
    private val prefs by lazy { getSharedPreferences("tv", MODE_PRIVATE) }
    private var irMode: Boolean
        get() = ir.available && prefs.getBoolean("ir", true)
        set(v) { prefs.edit().putBoolean("ir", v).apply() }
    private val handler = Handler(Looper.getMainLooper())
    private val repeatKeys = setOf("KEY_VOLUP", "KEY_VOLDOWN", "KEY_UP", "KEY_DOWN", "KEY_LEFT", "KEY_RIGHT", "KEY_REWIND", "KEY_FF")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = Banner(findViewById(R.id.banner))
        tv = SamsungTv(this) { msg ->
            // "Conectando…" e "Conectado" já aparecem na bolinha do topo
            if (msg != "Conectando…" && msg != "Conectado à TV") runOnUiThread { status.text = msg }
        }
        tv.onState = { st -> runOnUiThread { refreshPill(st) } }
        tv.onIcon = { runOnUiThread { scheduleRenderApps() } }
        tv.onLaunchFailed = { name -> runOnUiThread { relink(name) } }
        ir = IrRemote(this)
        appsRow = findViewById(R.id.apps)
        wire(findViewById(R.id.root))
        findViewById<View>(R.id.settings).setOnClickListener { showSettings() }
        findViewById<View>(R.id.power).setOnClickListener { buzz(Buzz.HEAVY); if (irMode) ir.send("KEY_POWER") else tv.power() }
        findViewById<View>(R.id.more).setOnClickListener { buzz(); showMore() }
        fitDpad()
        findViewById<View>(R.id.playPause).setOnClickListener {
            buzz()
            val playing = prefs.getBoolean("playing", true)
            press(if (playing) "KEY_PAUSE" else "KEY_PLAY")
            prefs.edit().putBoolean("playing", !playing).apply()
        }
        setupTouchPad()
        if (Build.VERSION.SDK_INT >= 33 && prefs.getBoolean("notif", true) && !prefs.getBoolean("askedNotif", false) &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("askedNotif", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
        }
        findViewById<View>(R.id.statusPill).setOnClickListener { buzz(); showConnection() }
        voiceButton(findViewById(R.id.voice))
        tv.onIme = { open -> runOnUiThread { tvKeyboardOpen = open; if (open && resumed) showKeyboard() else if (!open) keyboardDialog?.dismiss() } }
        renderApps()
        applyMode()
        // Aviso automático de versão nova, no máximo uma vez por dia
        if (System.currentTimeMillis() - prefs.getLong("lastUpdateCheck", 0) > 24 * 3600_000L) checkUpdate(silent = true)
        if (!irMode && tv.ip == null) scan()
    }

    private fun applyMode() {
        findViewById<android.widget.ImageView>(R.id.statusMode).setImageResource(if (irMode) R.drawable.ic_ir else R.drawable.ic_wifi)
        if (irMode) tv.disconnect() else tv.connect()
        refreshPill(null)
    }

    // ---------- Status (bolinha colorida) ----------

    private var autoScanned = false
    private var lastState: SamsungTv.State? = null

    /** event = estado novo vindo da TV; null = só redesenhar com o último estado. */
    private fun refreshPill(event: SamsungTv.State?) {
        if (event != null) lastState = event
        val st = lastState
        val (color, text) = when {
            irMode -> 0xFF64B5F6.toInt() to "Modo IR"
            tv.connected -> 0xFF4CAF50.toInt() to "Conectado"
            st == SamsungTv.State.CONNECTING -> 0xFFFFB300.toInt() to "Conectando…"
            tv.ip == null -> 0xFFE53935.toInt() to "TV não encontrada"
            else -> 0xFFE53935.toInt() to "TV desligada"
        }
        findViewById<View>(R.id.statusDot).background.setTint(color)
        findViewById<TextView>(R.id.statusLabel).text = text
        if (event == SamsungTv.State.CONNECTED) { autoScanned = false; scheduleRenderApps() }
        // Não conectou no IP salvo: procura a TV na rede uma vez (o roteador pode ter trocado o IP)
        if (event == SamsungTv.State.FAILED && !irMode && !autoScanned) {
            autoScanned = true
            val old = tv.ip
            thread {
                val ip = tv.scan()
                runOnUiThread { if (ip != null && ip != old) { status.text = "Achei a TV em outro endereço ($ip)"; tv.connect() } else if (ip != null) tv.connect() }
            }
        }
    }

    /** Aviso flutuante: aparece por alguns segundos e some (não empurra a tela). */
    private inner class Banner(private val view: TextView) {
        private val hide = Runnable { view.animate().alpha(0f).setDuration(250).withEndAction { view.visibility = View.GONE } }
        var text: CharSequence = ""
            set(v) {
                field = v
                if (v.isEmpty()) return
                view.text = v
                if (view.visibility != View.VISIBLE) { view.alpha = 0f; view.visibility = View.VISIBLE }
                view.animate().alpha(1f).setDuration(150)
                handler.removeCallbacks(hide)
                handler.postDelayed(hide, 3000L + v.length * 40L)
            }
        init { view.setOnClickListener { handler.removeCallbacks(hide); hide.run() } }
    }

    private fun press(key: String) = if (irMode) ir.send(key) else tv.key(key)

    private var resumed = false
    override fun onResume() {
        super.onResume(); resumed = true
        // Voltou da tela de permissão: continua a atualização
        pendingUpdate?.let { if (packageManager.canRequestPackageInstalls()) startUpdate(it) }
    }
    override fun onPause() { super.onPause(); resumed = false }
    override fun onStart() { super.onStart(); RemoteReceiver.hideNotification(this); if (!irMode) tv.connect() }
    override fun onStop() {
        super.onStop(); tv.disconnect()
        // Fora do app: volume e pausa ficam na notificação (e na tela de bloqueio)
        if (prefs.getBoolean("notif", true) && (Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED))
            RemoteReceiver.showNotification(this)
    }

    // ---------- Botões de volume do celular controlam a TV ----------

    private var lastVolKey = 0L
    private fun volKey(keyCode: Int) = if (!prefs.getBoolean("volKeys", true)) null else when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> "KEY_VOLUP"; KeyEvent.KEYCODE_VOLUME_DOWN -> "KEY_VOLDOWN"; else -> null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val key = volKey(keyCode) ?: return super.onKeyDown(keyCode, event)
        val now = SystemClock.uptimeMillis()
        if (event.repeatCount == 0 || now - lastVolKey >= 120) {
            lastVolKey = now; buzz(if (event.repeatCount == 0) Buzz.CLICK else Buzz.TICK); press(key)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (volKey(keyCode) != null) true else super.onKeyUp(keyCode, event)

    // ---------- Touchpad ----------

    private fun setupTouchPad() {
        val touch = findViewById<TouchPad>(R.id.touchpad)
        showTouchPad(prefs.getBoolean("touchpad", false))
        touch.onArrow = { k -> buzz(Buzz.TICK); press(k) }
        touch.onTap = {
            buzz()
            if (!touch.mouse) press("KEY_ENTER")
            else if (irMode) status.text = "O mouse só funciona no Wi-Fi" else tv.mouseClick()
        }
        touch.onMouse = { dx, dy -> if (!irMode) tv.mouseMove(dx, dy) }
        touch.onToggleMode = {
            buzz(Buzz.HEAVY)
            if (irMode && !touch.mouse) status.text = "O modo mouse só funciona no Wi-Fi: toque em Wi-Fi no topo"
            else {
                touch.mouse = !touch.mouse
                status.text = if (touch.mouse) "Modo mouse: use no navegador da TV" else "Modo setas: deslize para navegar"
            }
        }
    }

    private fun showTouchPad(on: Boolean) {
        findViewById<View>(R.id.touchpad).visibility = if (on) View.VISIBLE else View.GONE
        findViewById<View>(R.id.dpad).visibility = if (on) View.GONE else View.VISIBLE
        prefs.edit().putBoolean("touchpad", on).apply()
    }

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
    /** 0 = desligada, 1 = fraca, 2 = média, 3 = forte. */
    private var hapticLevel: Int
        get() = prefs.getInt("hapticLevel", if (prefs.getBoolean("haptic", true)) 2 else 0)
        set(v) { prefs.edit().putInt("hapticLevel", v).apply() }

    // Pulso com duração e força definidas: o "clique" padrão do Android é fraco demais em alguns celulares.
    private fun buzz(kind: Buzz = Buzz.CLICK, level: Int = hapticLevel) {
        val vib = vibrator ?: return
        if (level !in 1..3 || !vib.hasVibrator()) return
        val base = longArrayOf(0, 15, 25, 40)[level]
        val ms = when (kind) { Buzz.CLICK -> base; Buzz.TICK -> maxOf(8L, base / 2); Buzz.HEAVY -> base * 2 }
        val amp = if (vib.hasAmplitudeControl()) intArrayOf(0, 110, 190, 255)[level] else VibrationEffect.DEFAULT_AMPLITUDE
        vib.vibrate(VibrationEffect.createOneShot(ms, amp))
    }

    private fun showSettings() {
        val input = EditText(this).apply {
            hint = "Ex.: 192.168.0.15"
            inputType = InputType.TYPE_CLASS_PHONE
            setText(tv.ip ?: "")
        }
        val levels = listOf("Desligada", "Fraca", "Média", "Forte")
        val vibrate = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            levels.forEachIndexed { i, t -> addView(RadioButton(context).apply { id = 100 + i; text = t; textSize = 13f }) }
            check(100 + hapticLevel)
        }
        val chosen = { vibrate.checkedRadioButtonId - 100 }
        val test = Button(this).apply { text = "Testar vibração"; isAllCaps = false; setOnClickListener { buzz(level = chosen()) } }
        val update = Button(this).apply { text = "Verificar atualização (versão $myVersion)"; isAllCaps = false }
        val volKeys = CheckBox(this).apply { text = "Botões de volume do celular controlam a TV"; isChecked = prefs.getBoolean("volKeys", true) }
        val notif = CheckBox(this).apply { text = "Volume e pausa na notificação (fora do app)"; isChecked = prefs.getBoolean("notif", true) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
            addView(TextView(context).apply { text = "Vibração ao tocar"; setPadding(0, dp(12), 0, 0) })
            addView(vibrate); addView(test)
            addView(volKeys); addView(notif); addView(update)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Configurações")
            .setMessage((tv.model?.let { "TV: $it · voz: " + when (tv.voiceSupport) { true -> "sim"; false -> "não"; null -> "?" } + "\n\n" } ?: "") +
                "IP da TV — na TV: Configurações → Geral → Rede → Status da rede → Configurações de IP")
            .setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                hapticLevel = chosen()
                prefs.edit().putBoolean("volKeys", volKeys.isChecked).putBoolean("notif", notif.isChecked).apply()
                if (notif.isChecked && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) { tv.setIp(ip); tv.connect() }
            }
            .setNeutralButton("Procurar TV") { _, _ -> scan() }
            .setNegativeButton("Cancelar", null)
            .show()
        update.setOnClickListener { dialog.dismiss(); checkUpdate() }
    }

    /** Conexão: escolher IR ou Wi-Fi, reconectar ou procurar a TV. */
    private fun showConnection() {
        val modes = if (ir.available) arrayOf("Wi-Fi (rede)", "Infravermelho (IR)") else arrayOf("Wi-Fi (rede)")
        val info = tv.model?.let { "TV: $it" } ?: tv.ip?.let { "TV em $it" } ?: "TV ainda não encontrada"
        AlertDialog.Builder(this).setTitle("Conexão · $info")
            .setSingleChoiceItems(modes, if (irMode) 1 else 0) { d, w ->
                buzz(); val ir = w == 1
                if (ir != irMode) { irMode = ir; applyMode() }
                d.dismiss()
            }
            .setPositiveButton("Reconectar") { _, _ ->
                if (irMode) status.text = "Modo IR: aponte o topo do celular para a TV"
                else if (tv.ip == null) scan() else { autoScanned = false; tv.connect() }
            }
            .setNeutralButton("Procurar TV") { _, _ -> scan() }
            .setNegativeButton("Fechar", null)
            .show()
    }

    /** Painel "Mais" vindo de baixo: Fonte, Menu, Enter, teclado, touchpad, apps e números. */
    private fun showMore() {
        val sheet = layoutInflater.inflate(R.layout.more, null)
        wire(sheet)
        val dialog = AlertDialog.Builder(this).setView(sheet).create()
        val onPad = findViewById<View>(R.id.touchpad).visibility == View.VISIBLE
        sheet.findViewById<Button>(R.id.mPad).apply {
            text = if (onPad) "Direcional" else "Touchpad"
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, if (onPad) R.drawable.ic_dpad else R.drawable.ic_touch, 0, 0)
            setOnClickListener { buzz(); showTouchPad(!onPad); dialog.dismiss() }
        }
        sheet.findViewById<View>(R.id.mKeyboard).setOnClickListener {
            buzz(); dialog.dismiss()
            if (irMode) status.text = "O teclado só funciona no Wi-Fi: toque no status do topo" else showKeyboard()
        }
        sheet.findViewById<View>(R.id.mApps).setOnClickListener { buzz(); dialog.dismiss(); showAllApps() }
        dialog.window?.apply {
            setGravity(android.view.Gravity.BOTTOM)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** O direcional diminui se a tela for baixa, para tudo caber sem rolar. */
    private fun fitDpad() {
        val pads = listOf(findViewById<View>(R.id.dpad), findViewById<View>(R.id.touchpad))
        findViewById<View>(R.id.dpadBox).addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val s = ((bottom - top - dp(8)) / dp(264).toFloat()).coerceIn(0.5f, 1f)
            pads.forEach { it.scaleX = s; it.scaleY = s }
        }
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

    // ---------- Teclado e voz ----------

    private var tvKeyboardOpen = false
    private var keyboardDialog: AlertDialog? = null

    /** Só o Wi-Fi manda texto; pelo infravermelho não existe esse comando. */
    private fun canType(): Boolean {
        if (irMode) { status.text = "Para escrever, toque em Wi-Fi no topo"; return false }
        if (tv.ip == null) { status.text = "Toque em ⚙ para achar a TV no Wi-Fi primeiro"; return false }
        return true
    }

    private fun sendText(t: String) {
        if (t.isBlank() || !canType()) return
        tv.text(t); tv.textEnd()
        status.text = if (tvKeyboardOpen) "Enviado: $t" else "Enviado: $t (se nada aparecer, selecione o campo de texto na TV)"
    }

    /** Caixa de texto com o teclado do celular (Gboard tem o microfone para ditar). */
    private fun showKeyboard() {
        if (keyboardDialog?.isShowing == true || !canType()) return
        val input = EditText(this).apply {
            hint = "Digite ou toque no microfone do teclado"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
        val dialog = AlertDialog.Builder(this).setTitle("Escrever na TV").setView(box)
            .setPositiveButton("Enviar") { _, _ -> sendText(input.text.toString()) }
            .setNeutralButton("Ditar") { _, _ -> listen(SPEECH_TEXT) }
            .setNegativeButton("Fechar", null)
            .create()
        input.setOnEditorActionListener { _, action, e ->
            if (action == EditorInfo.IME_ACTION_SEARCH || e?.keyCode == KeyEvent.KEYCODE_ENTER) {
                buzz(); sendText(input.text.toString()); dialog.dismiss(); true
            } else false
        }
        dialog.setOnDismissListener { keyboardDialog = null }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        keyboardDialog = dialog
        dialog.show()
        input.requestFocus()
    }

    /** Toque: comando de voz do app. Segurar: tecla de voz da TV (experimental, só se a TV tiver microfone). */
    @SuppressLint("ClickableViewAccessibility")
    private fun voiceButton(v: View) {
        var holding = false
        val startHold = Runnable {
            if (irMode || tv.ip == null) return@Runnable
            holding = true; buzz(Buzz.HEAVY)
            tv.key("KEY_BT_VOICE", "Press")
            status.text = if (tv.voiceSupport == false) "Sua TV diz que não tem voz. Tentando mesmo assim… fale perto da TV"
                else "Assistente da TV: fale agora e solte o botão"
        }
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { view.isPressed = true; holding = false; handler.postDelayed(startHold, 600) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false; handler.removeCallbacks(startHold)
                    if (holding) tv.key("KEY_BT_VOICE", "Release")
                    else if (e.action == MotionEvent.ACTION_UP) { buzz(); listen(SPEECH_COMMAND) }
                }
            }
            true
        }
    }

    private fun listen(mode: Int) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, if (mode == SPEECH_TEXT) "Fale o que escrever na TV"
                else "Ex.: abrir Netflix, aumentar volume, canal 12, pesquisar…")
        try { startActivityForResult(intent, mode) }
        catch (_: ActivityNotFoundException) { status.text = "Este celular não tem reconhecimento de voz do Google" }
    }

    @Deprecated("API de Activity simples")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        if (resultCode != RESULT_OK) return
        if (requestCode == SPEECH_TEXT) sendText(said) else command(said)
    }

    private fun plain(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").trim()

    /** Aperta a tecla n vezes, com um intervalo curto entre elas. */
    private fun pressTimes(key: String, n: Int) = repeat(n.coerceIn(1, 30)) { i -> handler.postDelayed({ press(key) }, i * 180L) }

    /** "Alexa" do app: entende frases simples e aperta os botões. */
    private fun command(said: String) {
        val t = plain(said)
        val n = Regex("\\d+").find(t)?.value?.toIntOrNull()
        fun has(vararg w: String) = w.any { t.contains(it) }
        val rest = { prefix: Regex -> said.replace(prefix, "").trim() }
        val done = "Voz: \"$said\""
        when {
            t.matches(Regex("^(pesquisar|pesquisa|procurar|procura|buscar|busca|digitar|digita|escrever|escreve)\\b.*")) -> {
                sendText(rest(Regex("^\\S+\\s*(por\\s+)?", RegexOption.IGNORE_CASE))); return
            }
            t.matches(Regex("^(abrir|abre|abra|iniciar|inicia|entrar no|entrar na|ir para o|ir para a)\\b.*")) -> {
                val app = rest(Regex("^(abrir|abre|abra|iniciar|inicia|entrar n[oa]|ir para [oa])\\s*(o |a )?", RegexOption.IGNORE_CASE))
                val saved = loadApps().firstOrNull { plain(it.name).filter(Char::isLetterOrDigit) == plain(app).filter(Char::isLetterOrDigit) }
                if (irMode) { status.text = "Para abrir apps, troque para Wi-Fi"; return }
                if (saved != null) openShortcut(saved) else tv.launch(app, ""); return
            }
            has("desliga", "ligar", "liga a tv") -> { buzz(Buzz.HEAVY); if (irMode) ir.send("KEY_POWER") else tv.power() }
            has("tirar o mudo", "tira o mudo", "mudo", "silencia", "som de volta") -> press("KEY_MUTE")
            has("aumenta", "mais alto", "sobe o volume", "subir o volume", "volume mais") -> pressTimes("KEY_VOLUP", n ?: 3)
            has("diminui", "abaixa", "baixa o volume", "mais baixo", "volume menos") -> pressTimes("KEY_VOLDOWN", n ?: 3)
            has("proximo canal", "canal acima", "sobe canal", "subir canal") -> press("KEY_CHUP")
            has("canal anterior", "canal abaixo", "desce canal", "descer canal") -> press("KEY_CHDOWN")
            t.startsWith("canal") && n != null -> n.toString().forEachIndexed { i, c -> handler.postDelayed({ press("KEY_$c") }, i * 250L) }
            has("voltar", "volta") -> press("KEY_RETURN")
            has("inicio", "home", "tela inicial") -> press("KEY_HOME")
            has("menu", "configurac") -> press("KEY_MENU")
            has("fonte", "entrada", "hdmi") -> press("KEY_SOURCE")
            has("cima", "sobe") -> pressTimes("KEY_UP", n ?: 1)
            has("baixo", "desce") -> pressTimes("KEY_DOWN", n ?: 1)
            has("esquerda") -> pressTimes("KEY_LEFT", n ?: 1)
            has("direita") -> pressTimes("KEY_RIGHT", n ?: 1)
            t == "ok" || has("confirma", "enter", "seleciona") -> press("KEY_ENTER")
            tvKeyboardOpen -> { sendText(said); return }
            else -> { status.text = "Não entendi: \"$said\". Tente \"abrir Netflix\", \"aumentar volume\", \"pesquisar …\""; return }
        }
        status.text = done
    }

    // ---------- Atalhos de apps ----------

    private val defaultApps = """[
        {"name":"Netflix","id":"3201907018807"},
        {"name":"Prime Video","id":"3201910019365"},
        {"name":"Disney+","id":"3201901017640"},
        {"name":"Crunchyroll","id":""},
        {"name":"Xbox","id":"3202203026799"}]"""

    /** Atalho de app. power = "cena": liga a TV antes de abrir o app. */
    private data class Shortcut(val name: String, val id: String, val power: Boolean = false)

    private fun loadApps(): MutableList<Shortcut> {
        val arr = JSONArray(prefs.getString("apps", null) ?: defaultApps)
        return (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Shortcut(o.getString("name"), o.optString("id"), o.optBoolean("power")) } }
            .toMutableList()
    }

    private fun saveApps(list: List<Shortcut>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("id", it.id).put("power", it.power)) }
        prefs.edit().putString("apps", arr.toString()).apply()
        renderApps()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- Atalhos com logo ----------

    /** Cor da marca e inicial, usadas enquanto a logo verdadeira (vinda da TV) não chegou. */
    private val brands = listOf(
        "netflix" to (0xFFE50914 to "N"), "primevideo" to (0xFF00A8E1 to "prime"), "prime" to (0xFF00A8E1 to "prime"),
        "disney" to (0xFF0E2A8C to "D+"), "crunchyroll" to (0xFFF47521 to "C"), "xbox" to (0xFF107C10 to "X"),
        "youtube" to (0xFFFF0000 to "▶"), "globoplay" to (0xFFFF1E4F to "g"), "max" to (0xFF002BE7 to "max"),
        "hbo" to (0xFF5822B4 to "HBO"), "spotify" to (0xFF1DB954 to "S"), "twitch" to (0xFF9146FF to "T"),
        "paramount" to (0xFF0064FF to "P+"), "appletv" to (0xFF1C1C1E to "tv"), "pluto" to (0xFF2D2D2D to "P"),
        "deezer" to (0xFFA238FF to "D"), "starplus" to (0xFF0E2A8C to "D+")
    )
    private val palette = intArrayOf(0xFF5C6BC0.toInt(), 0xFF26A69A.toInt(), 0xFFAB47BC.toInt(), 0xFFEF6C00.toInt(), 0xFF8D6E63.toInt())

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Logo guardada do app (a TV manda quando conecta pelo Wi-Fi). */
    private fun iconFor(name: String, id: String): android.graphics.Bitmap? {
        val key = "icon_" + norm(name)
        val appId = tv.findApp(name)?.id?.also { prefs.edit().putString(key, it).apply() }
            ?: prefs.getString(key, null) ?: id.ifEmpty { return null }
        val f = tv.iconFile(appId)
        return if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.path) else null
    }

    private fun rounded(color: Int) = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(color) }

    private fun logoView(name: String, id: String): View {
        iconFor(name, id)?.let { bmp ->
            return android.widget.ImageView(this).apply {
                setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                background = rounded(0xFF000000.toInt()); clipToOutline = true
            }
        }
        val n = norm(name)
        val (color, mono) = brands.firstOrNull { n.contains(it.first) }?.second?.let { it.first.toInt() to it.second }
            ?: (palette[Math.floorMod(n.hashCode(), palette.size)] to name.take(1).uppercase())
        return monogram(mono, color)
    }

    private fun monogram(text: String, color: Int) = TextView(this).apply {
        this.text = text; gravity = android.view.Gravity.CENTER; setTextColor(Color.WHITE)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textSize = when { text.length >= 4 -> 9f; text.length == 3 -> 10.5f; else -> 15f }
        background = rounded(color)
    }

    private val renderAppsRunnable = Runnable { renderApps() }
    /** Junta vários ícones que chegam juntos numa só atualização da tela. */
    private fun scheduleRenderApps() { handler.removeCallbacks(renderAppsRunnable); handler.postDelayed(renderAppsRunnable, 300) }

    /** Uma linha de logos: até 6 apps + "Adicionar" (ou "Mais apps" se tiver mais de 6). */
    private fun renderApps() {
        appsRow.removeAllViews()
        val apps = loadApps()
        val slots = 7
        val shown = if (apps.size < slots) apps.size else slots - 1
        apps.take(shown).forEachIndexed { i, sc ->
            appsRow.addView(appTile(sc.name, logoView(sc.name, sc.id), sc.power) { openShortcut(sc) }.also { enableDrag(it, i) })
        }
        appsRow.addView(if (apps.size >= slots) appTile("Todos os apps", monogram("⋯", 0xFF3A4150.toInt()), false) { showAllApps() }
            else appTile("Adicionar app", monogram("+", 0xFF3A4150.toInt()), false) { addApp() })
        repeat(slots - appsRow.childCount) { appsRow.addView(View(this), LinearLayout.LayoutParams(0, dp(52), 1f)) }
    }

    /** Logo do app (sem texto); cena = bolinha vermelha no canto. */
    private fun appTile(name: String, logo: View, scene: Boolean, click: () -> Unit) = android.widget.FrameLayout(this).apply {
        contentDescription = if (scene) "$name (liga a TV e abre)" else name
        background = getDrawable(R.drawable.touch_round)
        stateListAnimator = AnimatorInflater.loadStateListAnimator(context, R.animator.press)
        addView(logo, android.widget.FrameLayout.LayoutParams(dp(42), dp(42), android.view.Gravity.CENTER))
        if (scene) addView(View(context).apply { background = getDrawable(R.drawable.dot); background.setTint(0xFFE53935.toInt()) },
            android.widget.FrameLayout.LayoutParams(dp(10), dp(10), android.view.Gravity.TOP or android.view.Gravity.END).apply { setMargins(0, dp(2), dp(4), 0) })
        layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
        setOnClickListener { buzz(); click() }
    }

    private fun showAllApps() {
        val apps = loadApps()
        AlertDialog.Builder(this).setTitle("Apps")
            .setAdapter(appAdapter(apps.map { it.name to it.id })) { _, w -> openShortcut(apps[w]) }
            .setNeutralButton("Adicionar") { _, _ -> addApp() }
            .setNegativeButton("Editar") { _, _ ->
                AlertDialog.Builder(this).setTitle("Editar qual?")
                    .setItems(apps.map { it.name }.toTypedArray()) { _, w -> editApp(w) }.show()
            }
            .show()
    }

    /** Abre o app; se for "cena" e a TV estiver desligada, liga antes (Wake-on-LAN). */
    private fun openShortcut(sc: Shortcut) {
        if (sc.power && !tv.connected) {
            status.text = "Ligando a TV e abrindo ${sc.name}…"
            tv.power()
        }
        tv.launch(sc.name, sc.id)
    }

    /** Segurar e arrastar troca o atalho de lugar; segurar e soltar no mesmo lugar abre o menu. */
    private fun enableDrag(v: View, index: Int) {
        v.setOnLongClickListener {
            buzz(Buzz.HEAVY)
            v.startDragAndDrop(android.content.ClipData.newPlainText("atalho", index.toString()), View.DragShadowBuilder(v), index, 0)
            true
        }
        v.setOnDragListener { view, e ->
            when (e.action) {
                DragEvent.ACTION_DRAG_ENTERED -> view.alpha = 0.5f
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> view.alpha = 1f
                DragEvent.ACTION_DROP -> {
                    view.alpha = 1f
                    val from = e.localState as? Int ?: return@setOnDragListener false
                    if (from == index) editApp(index)
                    else { buzz(); saveApps(loadApps().apply { add(index, removeAt(from)) }) }
                }
            }
            true
        }
    }

    /** Lista com a logo de cada app (usada para escolher apps da TV e na lista "Mais"). */
    private fun appAdapter(items: List<Pair<String, String>>) = object : android.widget.BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convertView: View?, parent: ViewGroup) = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(10))
            addView(logoView(items[p].first, items[p].second), LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(TextView(context).apply { text = items[p].first; textSize = 16f; setPadding(dp(16), 0, 0, 0) })
        }
    }

    private fun editApp(i: Int) {
        val list = loadApps()
        val sc = list[i]
        status.text = "Dica: segure e arraste um atalho para trocar de lugar"
        AlertDialog.Builder(this).setTitle(sc.name)
            .setItems(arrayOf("Escolher o app da TV",
                if (sc.power) "Não ligar a TV junto" else "Ligar a TV e abrir (cena)", "Remover atalho")) { _, which ->
                when (which) {
                    0 -> pickFromTv { name, id -> list[i] = sc.copy(id = id); saveApps(list); toast("$name vinculado") }
                    1 -> {
                        list[i] = sc.copy(power = !sc.power); saveApps(list)
                        status.text = if (!sc.power) "${sc.name}: agora liga a TV e abre o app" else "${sc.name}: só abre o app"
                    }
                    else -> { list.removeAt(i); saveApps(list) }
                }
            }.show()
    }

    /** O código do app não existe nesta TV (404): oferece escolher o certo na lista da TV. */
    private fun relink(name: String) {
        val list = loadApps()
        val i = list.indexOfFirst { it.name == name }
        if (i < 0 || isFinishing) return
        AlertDialog.Builder(this).setTitle("$name não abriu")
            .setMessage("A TV disse que esse app não está instalado com esse código. Quer escolher o app certo na lista da TV?")
            .setPositiveButton("Escolher") { _, _ ->
                pickFromTv { n, id -> list[i] = list[i].copy(id = id); saveApps(list); status.text = "$name agora abre \"$n\"" }
            }
            .setNegativeButton("Remover atalho") { _, _ -> list.removeAt(i); saveApps(list) }
            .setNeutralButton("Fechar", null).show()
    }

    private fun addApp() = pickFromTv { name, id -> saveApps(loadApps().apply { add(Shortcut(name, id)) }) }

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
            .setAdapter(appAdapter(apps.map { it.name to it.id })) { _, w -> done(apps[w].name, apps[w].id) }
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
    // Baixa o APK do GitHub e abre a tela "Instalar" do Android direto (sem navegador).

    private val myVersion: Long get() = packageManager.getPackageInfo(packageName, 0).longVersionCode
    private var pendingUpdate: Long? = null

    private fun info(title: String, msg: String) =
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()

    /** silent = verificação automática ao abrir: só aparece se houver versão nova. */
    private fun checkUpdate(silent: Boolean = false) {
        if (!silent) status.text = "Verificando atualização…"
        thread {
            var why = ""
            val latest = runCatching {
                val c = URL(RELEASES_API).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                when (val code = c.responseCode) {
                    200 -> Unit
                    404 -> error("o repositório está privado ou sem versões publicadas (404)")
                    403, 429 -> error("o GitHub limitou os acessos por agora ($code). Tente daqui a pouco")
                    else -> error("o GitHub respondeu $code")
                }
                JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    .getString("tag_name").removePrefix("v").toLong()
            }.onFailure { why = it.message ?: it.javaClass.simpleName }.getOrNull()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                prefs.edit().putLong("lastUpdateCheck", System.currentTimeMillis()).apply()
                when {
                    latest == null -> if (!silent) info("Atualização", "Não consegui verificar.\n\nMotivo: $why")
                    latest <= myVersion -> if (!silent) info("Atualização", "Você já tem a versão mais nova ($myVersion).")
                    else -> AlertDialog.Builder(this)
                        .setTitle("Versão $latest disponível")
                        .setMessage("Você tem a versão $myVersion. Atualizar agora?")
                        .setPositiveButton("Atualizar") { _, _ -> startUpdate(latest) }
                        .setNegativeButton("Agora não", null).show()
                }
                if (!silent) status.text = if (irMode) "Infravermelho: aponte o celular para a TV" else if (tv.connected) "Conectado à TV" else ""
            }
        }
    }

    private fun startUpdate(ver: Long) {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingUpdate = ver
            AlertDialog.Builder(this).setTitle("Permitir atualizações")
                .setMessage("Na próxima tela, ative \"Permitir desta fonte\" para o Controle TV e volte. Só precisa fazer isso uma vez.")
                .setPositiveButton("Abrir") { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Cancelar") { _, _ -> pendingUpdate = null }.show()
            return
        }
        pendingUpdate = null
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val label = TextView(this).apply { text = "Baixando…"; setPadding(0, 0, 0, dp(8)) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(8)); addView(label); addView(bar)
        }
        val progress = AlertDialog.Builder(this).setTitle("Atualizando para a versão $ver").setView(box).setCancelable(false).show()
        thread {
            val error = runCatching {
                val c = URL("$RELEASES/download/v$ver/controle-tv.apk")
                    .openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 20000
                val total = c.contentLengthLong
                val installer = packageManager.packageInstaller
                val params = android.content.pm.PackageInstaller.SessionParams(
                    android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    c.inputStream.use { input ->
                        session.openWrite("controle-tv.apk", 0, if (total > 0) total else -1).use { out ->
                            val buf = ByteArray(64 * 1024); var done = 0L; var last = -1
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                                if (pct != last) { last = pct; runOnUiThread { bar.progress = pct; label.text = "Baixando… $pct%" } }
                            }
                            session.fsync(out)
                        }
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getActivity(this, 7,
                        Intent(this, MainActivity::class.java).setAction(ACTION_INSTALLED), flags)
                    session.commit(pi.intentSender)
                }
            }.exceptionOrNull()
            runOnUiThread {
                progress.dismiss()
                if (error != null) updateFailed(ver, error.message ?: "erro ao baixar")
            }
        }
    }

    /** Resposta do instalador do Android (chega aqui pelo launchMode singleTop). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action != ACTION_INSTALLED) return
        when (intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999)) {
            android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let { startActivity(it) }
            }
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> Unit
            android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED -> status.text = "Atualização cancelada"
            else -> updateFailed(null, intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "o Android recusou a instalação")
        }
    }

    /** Se o instalador interno falhar (alguns celulares bloqueiam), oferece o navegador. */
    private fun updateFailed(ver: Long?, why: String) {
        val url = if (ver != null) "$RELEASES/download/v$ver/controle-tv.apk"
            else "$RELEASES/latest/download/controle-tv.apk"
        AlertDialog.Builder(this).setTitle("Não deu para atualizar")
            .setMessage("Motivo: $why\n\nQuer baixar pelo navegador?")
            .setPositiveButton("Abrir navegador") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .setNegativeButton("Fechar", null).show()
    }
}
