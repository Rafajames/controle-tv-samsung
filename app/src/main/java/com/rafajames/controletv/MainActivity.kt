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
    }

    private lateinit var tv: SamsungTv
    private lateinit var status: TextView
    private lateinit var ir: IrRemote
    private lateinit var modeBtn: Button
    private lateinit var apps1: LinearLayout
    private lateinit var apps2: LinearLayout
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
        apps1 = findViewById(R.id.apps1)
        apps2 = findViewById(R.id.apps2)
        wire(findViewById(R.id.root))
        findViewById<View>(R.id.settings).setOnClickListener { showSettings() }
        findViewById<View>(R.id.power).setOnClickListener { buzz(Buzz.HEAVY); if (irMode) ir.send("KEY_POWER") else tv.power() }
        findViewById<View>(R.id.dialpad).setOnClickListener { buzz(); showNumpad() }
        fitDpad()
        modeBtn.setOnClickListener {
            buzz()
            if (!ir.available) { status.text = "Este celular não tem infravermelho"; return@setOnClickListener }
            irMode = !irMode; applyMode()
        }
        findViewById<View>(R.id.keyboard).setOnClickListener { buzz(); showKeyboard() }
        voiceButton(findViewById(R.id.voice))
        tv.onIme = { open -> runOnUiThread { tvKeyboardOpen = open; if (open && resumed) showKeyboard() else if (!open) keyboardDialog?.dismiss() } }
        renderApps()
        applyMode()
        // Aviso automático de versão nova, no máximo uma vez por dia
        if (System.currentTimeMillis() - prefs.getLong("lastUpdateCheck", 0) > 24 * 3600_000L) checkUpdate(silent = true)
        if (!irMode && tv.ip == null) scan()
    }

    private fun applyMode() {
        modeBtn.text = if (irMode) "IR" else "Wi-Fi"
        modeBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(if (irMode) R.drawable.ic_ir else R.drawable.ic_wifi, 0, 0, 0)
        if (irMode) { tv.disconnect(); status.text = "Infravermelho: aponte o celular para a TV" }
        else tv.connect()
    }

    private fun press(key: String) = if (irMode) ir.send(key) else tv.key(key)

    private var resumed = false
    override fun onResume() {
        super.onResume(); resumed = true
        // Voltou da tela de permissão: continua a atualização
        pendingUpdate?.let { if (packageManager.canRequestPackageInstalls()) startUpdate(it) }
    }
    override fun onPause() { super.onPause(); resumed = false }
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
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
            addView(TextView(context).apply { text = "Vibração ao tocar"; setPadding(0, dp(12), 0, 0) })
            addView(vibrate); addView(test); addView(update)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Configurações")
            .setMessage((tv.model?.let { "TV: $it · voz: " + when (tv.voiceSupport) { true -> "sim"; false -> "não"; null -> "?" } + "\n\n" } ?: "") +
                "IP da TV — na TV: Configurações → Geral → Rede → Status da rede → Configurações de IP")
            .setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                hapticLevel = chosen()
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) { tv.setIp(ip); tv.connect() }
            }
            .setNeutralButton("Procurar TV") { _, _ -> scan() }
            .setNegativeButton("Cancelar", null)
            .show()
        update.setOnClickListener { dialog.dismiss(); checkUpdate() }
    }

    /** Painel de números por cima do controle (não estica a tela). */
    private fun showNumpad() {
        val pad = layoutInflater.inflate(R.layout.numpad, null)
        wire(pad)
        val dialog = AlertDialog.Builder(this).setView(pad).create()
        pad.findViewById<View>(R.id.numClose).setOnClickListener { buzz(); dialog.dismiss() }
        dialog.show()
    }

    /** O direcional diminui se a tela for baixa, para tudo caber sem rolar. */
    private fun fitDpad() {
        val pad = findViewById<View>(R.id.dpad)
        findViewById<View>(R.id.dpadBox).addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val s = ((bottom - top - dp(8)) / dp(264).toFloat()).coerceIn(0.5f, 1f)
            pad.scaleX = s; pad.scaleY = s
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
        if (irMode) { status.text = "Para escrever, troque para Wi-Fi (botão IR no topo)"; return false }
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
                val saved = loadApps().firstOrNull { plain(it.first).filter(Char::isLetterOrDigit) == plain(app).filter(Char::isLetterOrDigit) }
                if (irMode) { status.text = "Para abrir apps, troque para Wi-Fi"; return }
                tv.launch(saved?.first ?: app, saved?.second ?: ""); return
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
        textSize = 12f
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        background = getDrawable(R.drawable.btn)
        stateListAnimator = AnimatorInflater.loadStateListAnimator(context, R.animator.press)
        setPadding(dp(4), 0, dp(4), 0)
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
    }

    /** Grade fixa de 8 lugares (4 + 4). Com mais de 7 apps, o último vira "Mais". */
    private fun renderApps() {
        apps1.removeAllViews(); apps2.removeAllViews()
        val apps = loadApps()
        val shown = if (apps.size <= 7) apps.size else 7
        val cells = mutableListOf<View>()
        apps.take(shown).forEachIndexed { i, (name, id) ->
            cells += chip(name).apply {
                setOnClickListener { buzz(); tv.launch(name, id) }
                setOnLongClickListener { editApp(i); true }
            }
        }
        cells += if (apps.size > 7) chip("Mais…").apply { setOnClickListener { buzz(); showAllApps() } }
            else chip("＋").apply { setOnClickListener { buzz(); addApp() } }
        while (cells.size < 8) cells += View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) } }
        cells.forEachIndexed { i, v -> (if (i < 4) apps1 else apps2).addView(v) }
    }

    private fun showAllApps() {
        val apps = loadApps()
        AlertDialog.Builder(this).setTitle("Apps")
            .setItems(apps.map { it.first }.toTypedArray()) { _, w -> tv.launch(apps[w].first, apps[w].second) }
            .setNeutralButton("Adicionar") { _, _ -> addApp() }
            .setNegativeButton("Editar") { _, _ ->
                AlertDialog.Builder(this).setTitle("Editar qual?")
                    .setItems(apps.map { it.first }.toTypedArray()) { _, w -> editApp(w) }.show()
            }
            .show()
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
    // Baixa o APK do GitHub e abre a tela "Instalar" do Android direto (sem navegador).

    private val myVersion: Long get() = packageManager.getPackageInfo(packageName, 0).longVersionCode
    private var pendingUpdate: Long? = null

    private fun info(title: String, msg: String) =
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()

    /** silent = verificação automática ao abrir: só aparece se houver versão nova. */
    private fun checkUpdate(silent: Boolean = false) {
        if (!silent) status.text = "Verificando atualização…"
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
                if (isFinishing) return@runOnUiThread
                prefs.edit().putLong("lastUpdateCheck", System.currentTimeMillis()).apply()
                when {
                    latest == null -> if (!silent) info("Atualização", "Não consegui verificar. Confira a internet e tente de novo.")
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
                val c = URL("https://github.com/Rafajames/controle-tv-samsung/releases/download/v$ver/controle-tv.apk")
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
        val url = if (ver != null) "https://github.com/Rafajames/controle-tv-samsung/releases/download/v$ver/controle-tv.apk"
            else "https://github.com/Rafajames/controle-tv-samsung/releases/latest/download/controle-tv.apk"
        AlertDialog.Builder(this).setTitle("Não deu para atualizar")
            .setMessage("Motivo: $why\n\nQuer baixar pelo navegador?")
            .setPositiveButton("Abrir navegador") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .setNegativeButton("Fechar", null).show()
    }
}
