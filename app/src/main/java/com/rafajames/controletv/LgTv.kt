package com.rafajames.controletv

import android.content.Context
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * LG webOS (2014+). WebSocket na porta 3001 (wss, firmwares novos) ou 3000 (antigos).
 * Na primeira vez a TV mostra um aviso para permitir; a chave fica guardada no celular.
 */
class LgTv(private val ctx: Context, status: (String) -> Unit) : Tv(ctx, status) {
    override val brand = "lg"
    override val brandName = "LG"
    override val hasMouse = true

    private var ws: WebSocket? = null
    private var pointer: WebSocket? = null
    @Volatile private var connecting = false
    @Volatile private var pending: (() -> Unit)? = null
    private val ids = AtomicInteger(0)
    private val callbacks = java.util.concurrent.ConcurrentHashMap<String, (JSONObject) -> Unit>()

    /** Teclas da tela (nomes Samsung) → botões do LG. */
    private val buttons = mapOf(
        "KEY_UP" to "UP", "KEY_DOWN" to "DOWN", "KEY_LEFT" to "LEFT", "KEY_RIGHT" to "RIGHT",
        "KEY_ENTER" to "ENTER", "KEY_RETURN" to "BACK", "KEY_HOME" to "HOME", "KEY_MENU" to "MENU",
        "KEY_VOLUP" to "VOLUMEUP", "KEY_VOLDOWN" to "VOLUMEDOWN", "KEY_MUTE" to "MUTE",
        "KEY_CHUP" to "CHANNELUP", "KEY_CHDOWN" to "CHANNELDOWN",
        "KEY_PLAY" to "PLAY", "KEY_PAUSE" to "PAUSE", "KEY_REWIND" to "REWIND", "KEY_FF" to "FASTFORWARD",
        "KEY_STOP" to "STOP", "KEY_INFO" to "INFO",
    ) + (0..9).associate { "KEY_$it" to "$it" }

    override fun connect() {
        val host = ip ?: return
        if (connected || connecting) return
        connecting = true
        state(State.CONNECTING)
        open("wss://$host:3001", fallback = "ws://$host:3000")
    }

    private fun open(url: String, fallback: String?) {
        ws = lanClient.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { register(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                val payload = msg.optJSONObject("payload") ?: JSONObject()
                if (msg.optString("type") == "error") lastError = msg.optString("error")
                if (msg.optString("id") == "reg") {
                    when {
                        msg.optString("type") == "registered" -> {
                            payload.optString("client-key").takeIf { it.isNotEmpty() }?.let { prefs.edit().putString("lgKey_$ip", it).apply() }
                            connecting = false
                            state(State.CONNECTED)
                            status("Conectado à TV")
                            afterConnect()
                        }
                        msg.optString("type") == "error" -> {
                            connecting = false; state(State.FAILED)
                            status("A TV LG recusou. Toque num botão e escolha Permitir/Sim na TV")
                        }
                        else -> status("Na TV LG, escolha Permitir/Sim para liberar o controle")
                    }
                    return
                }
                callbacks.remove(msg.optString("id"))?.invoke(payload)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket != ws) return
                if (fallback != null && !connected) { open(fallback, null); return }
                connecting = false; pointer = null
                state(State.FAILED)
                status("Não achei a TV LG. Se estiver desligada, toque no botão vermelho para ligar")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket != ws) return
                connecting = false; pointer = null; state(State.OFFLINE)
            }
        })
    }

    private fun register(w: WebSocket) {
        val pairing = runCatching { JSONObject(ctx.assets.open("lg_pairing.json").bufferedReader().readText()) }.getOrElse { JSONObject() }
        prefs.getString("lgKey_$ip", null)?.let { pairing.put("client-key", it) }
        w.send(JSONObject().put("id", "reg").put("type", "register").put("payload", pairing).toString())
    }

    private fun afterConnect() {
        request("ssap://system/getSystemInfo") { p -> model = p.optString("modelName").ifEmpty { null } }
        // Endereço MAC para ligar a TV pela rede depois
        request("ssap://com.webos.service.connectionmanager/getinfo") { p ->
            val mac = p.optJSONObject("wifiInfo")?.optString("macAddress")?.ifEmpty { null }
                ?: p.optJSONObject("wiredInfo")?.optString("macAddress")?.ifEmpty { null }
            mac?.let { prefs.edit().putString("lgMac_$ip", it).apply() }
        }
        openPointer()
        refreshApps()
        pending?.invoke(); pending = null
    }

    private fun request(uri: String, payload: JSONObject = JSONObject(), cb: ((JSONObject) -> Unit)? = null) {
        val id = "r" + ids.incrementAndGet()
        cb?.let { callbacks[id] = it }
        ws?.send(JSONObject().put("id", id).put("type", "request").put("uri", uri).put("payload", payload).toString())
    }

    /** Sem conexão: guarda o comando e conecta; ele sai assim que a TV aceitar. */
    private fun whenReady(action: () -> Unit) { if (connected) action() else { pending = action; connect() } }

    private fun openPointer() = request("ssap://com.webos.service.networkinput/getPointerInputSocket") { p ->
        val path = p.optString("socketPath").ifEmpty { return@request }
        pointer = lanClient.newWebSocket(Request.Builder().url(path).build(), object : WebSocketListener() {
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (webSocket == pointer) pointer = null }
        })
    }

    private fun pointerSend(type: String, vararg kv: Pair<String, Any>) {
        val p = pointer ?: run { openPointer(); return }
        p.send((listOf("type:$type") + kv.map { "${it.first}:${it.second}" }).joinToString("\n") + "\n\n")
    }

    override fun key(k: String, cmd: String) {
        if (cmd == "Release") return
        when (k) {
            "KEY_POWER" -> power()
            "KEY_SOURCE" -> whenReady { request("ssap://system.launcher/launch", JSONObject().put("id", "com.webos.app.inputpicker")) }
            else -> {
                val b = buttons[k] ?: return
                whenReady { pointerSend("button", "name" to b) }
            }
        }
    }

    override fun text(t: String) = whenReady { request("ssap://com.webos.service.ime/insertText", JSONObject().put("text", t).put("replace", 0)) }
    override fun textEnd() { if (connected) request("ssap://com.webos.service.ime/sendEnterKey") }
    override fun mouseMove(dx: Int, dy: Int) { if (connected) pointerSend("move", "dx" to dx, "dy" to dy, "down" to 0) }
    override fun mouseClick() { if (connected) pointerSend("click") }

    override fun refreshApps() = whenReady {
        request("ssap://com.webos.applicationManager/listLaunchPoints") { p ->
            val arr = p.optJSONArray("launchPoints") ?: return@request
            installed = (0 until arr.length()).map { arr.getJSONObject(it) }
                .map { TvApp(it.optString("title"), it.optString("id"), 2, it.optString("icon")) }
                .filter { it.id.isNotEmpty() }.sortedBy { it.name.lowercase() }
            appsSource = "TV"
            runCatching { onIcon?.invoke() }
            thread { fetchIcons() }
        }
    }

    private fun fetchIcons() {
        installed.filter { it.icon.startsWith("http") && !iconFile(it.id).exists() }.forEach { a ->
            runCatching {
                lanClient.newCall(Request.Builder().url(a.icon).build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.bytes()?.let { iconFile(a.id).writeBytes(it) }
                }
            }
        }
        onIcon?.invoke()
    }

    override fun launch(name: String, id: String) {
        if (ip == null) { status("Toque em ⚙ para escolher a TV primeiro"); return }
        whenReady {
            val appId = findApp(name)?.id ?: id.ifEmpty { knownIds.entries.firstOrNull { norm(name).contains(it.key) }?.value }
            if (appId == null) { status("Não achei \"$name\" na TV LG"); onLaunchFailed?.invoke(name); return@whenReady }
            status("Abrindo $name")
            request("ssap://system.launcher/launch", JSONObject().put("id", appId))
        }
    }

    private val knownIds = mapOf(
        "netflix" to "netflix", "youtube" to "youtube.leanback.v4", "prime" to "amazon",
        "disney" to "com.disney.disneyplus-prod", "globoplay" to "globoplay", "spotify" to "spotify-beehive",
        "appletv" to "com.apple.appletv", "browser" to "com.webos.app.browser", "internet" to "com.webos.app.browser",
    )

    override fun power() {
        if (connected) { request("ssap://system/turnOff"); return }
        val mac = prefs.getString("lgMac_$ip", null)
        if (mac == null) { status("Ligue a TV LG pelo controle normal uma vez com o app aberto, para eu aprender o endereço dela"); return }
        status("Ligando a TV LG… (na TV: Geral › Dispositivos › Ligar via Wi‑Fi precisa estar ativado)")
        thread {
            wake(mac)
            repeat(8) { Thread.sleep(2500); if (connected) return@thread; connect() }
        }
    }

    override fun disconnect() {
        pointer?.close(1000, null); pointer = null
        ws?.close(1000, null); ws = null
        connecting = false
        state(State.OFFLINE)
    }
}
