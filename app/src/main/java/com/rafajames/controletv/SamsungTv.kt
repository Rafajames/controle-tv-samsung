package com.rafajames.controletv

import android.content.Context
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/** Fala com TVs Samsung Tizen (2016+) pela rede local, porta 8002. */
class SamsungTv(ctx: Context, private val status: (String) -> Unit) {

    enum class State { CONNECTING, CONNECTED, FAILED, OFFLINE }
    /** App instalado na TV. icon = caminho do ícone dentro da TV. */
    data class TvApp(val name: String, val id: String, val type: Int, val icon: String)

    /** Estado da conexão (para a bolinha colorida) e aviso quando chega um ícone novo. */
    @Volatile var onState: ((State) -> Unit)? = null
    @Volatile var onIcon: (() -> Unit)? = null
    private val iconDir = File(ctx.filesDir, "icons").apply { mkdirs() }
    fun iconFile(appId: String) = File(iconDir, appId.filter { it.isLetterOrDigit() || it == '.' } + ".png")

    private val prefs = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)
    val ip: String? get() = prefs.getString("ip", null)

    @Volatile var connected = false; private set
    @Volatile private var connecting = false
    @Volatile private var pending: String? = null
    @Volatile private var pendingLaunch: Pair<String, String>? = null
    @Volatile var installed: List<TvApp> = emptyList(); private set
    private var ws: WebSocket? = null
    /** Avisado quando a TV abre (true) ou fecha (false) o teclado dela. */
    @Volatile var onIme: ((Boolean) -> Unit)? = null

    // A TV usa certificado próprio: aceitamos qualquer um (só falamos com a TV da rede local)
    private val client: OkHttpClient = run {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(4, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    fun setIp(newIp: String) {
        if (newIp != ip) prefs.edit().putString("ip", newIp).remove("token").remove("mac").apply()
        disconnect()
    }

    fun connect() {
        val host = ip ?: return
        if (connected || connecting) return
        connecting = true
        onState?.invoke(State.CONNECTING)
        val token = prefs.getString("token", null)
        status(if (token == null) "Conectando… aceite o aviso na TV" else "Conectando…")
        val name = Base64.encodeToString("Controle Poco".toByteArray(), Base64.NO_WRAP)
        val url = "wss://$host:8002/api/v2/channels/samsung.remote.control?name=$name" +
            (token?.let { "&token=$it" } ?: "")
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("event")) {
                    "ms.channel.connect" -> {
                        connected = true; connecting = false
                        onState?.invoke(State.CONNECTED)
                        msg.optJSONObject("data")?.optString("token")?.takeIf { it.isNotEmpty() }
                            ?.let { prefs.edit().putString("token", it).apply() }
                        status("Conectado à TV")
                        pending?.let { webSocket.send(it) }; pending = null
                        webSocket.send(emit("ed.installedApp.get", null))
                        thread { Thread.sleep(2500); runPendingLaunch() }
                        learnMac(host)
                    }
                    "ed.installedApp.get" -> {
                        val arr = msg.optJSONObject("data")?.optJSONArray("data")
                        if (arr != null) installed = (0 until arr.length()).map {
                            val a = arr.getJSONObject(it)
                            TvApp(a.optString("name"), a.optString("appId"), a.optInt("app_type", 2), a.optString("icon"))
                        }.filter { it.id.isNotEmpty() }.sortedBy { it.name.lowercase() }
                        runPendingLaunch()
                        // Pede à TV o ícone de cada app que ainda não está guardado no celular
                        installed.filter { it.icon.isNotEmpty() && !iconFile(it.id).exists() }.forEach {
                            webSocket.send(emit("ed.apps.icon", JSONObject().put("iconPath", it.icon)))
                        }
                    }
                    "ed.apps.icon" -> {
                        val d = msg.optJSONObject("data") ?: return
                        val app = installed.firstOrNull { it.icon == d.optString("iconPath") } ?: return
                        val img = d.optString("imageData").ifEmpty { return }
                        runCatching { iconFile(app.id).writeBytes(Base64.decode(img, Base64.DEFAULT)) }
                            .onSuccess { onIcon?.invoke() }
                    }
                    "ms.remote.imeStart" -> onIme?.invoke(true)
                    "ms.remote.imeEnd" -> onIme?.invoke(false)
                    "ms.channel.unauthorized" -> {
                        connecting = false
                        onState?.invoke(State.FAILED)
                        status("A TV recusou. Toque num botão e aceite o aviso na TV")
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket != ws) return
                connected = false; connecting = false
                onState?.invoke(State.FAILED)
                status("Não achei a TV. Se estiver desligada, toque no botão vermelho para ligar")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket != ws) return
                connected = false; connecting = false
                onState?.invoke(State.OFFLINE)
            }
        })
    }

    fun disconnect() {
        ws?.close(1000, null); ws = null
        connected = false; connecting = false
        onState?.invoke(State.OFFLINE)
    }

    /** cmd: "Click" (apertar e soltar), "Press" (segurar) ou "Release" (soltar). */
    fun key(k: String, cmd: String = "Click") = control(JSONObject()
        .put("Cmd", cmd).put("DataOfCmd", k)
        .put("Option", "false").put("TypeOfRemote", "SendRemoteKey"))

    /** Escreve no campo de texto aberto na TV (navegador, pesquisa…). */
    fun text(t: String) = control(JSONObject()
        .put("Cmd", Base64.encodeToString(t.toByteArray(), Base64.NO_WRAP))
        .put("DataOfCmd", "base64").put("TypeOfRemote", "SendInputString"))

    /** Move o cursor do mouse da TV (navegador) em pixels relativos. */
    fun mouseMove(dx: Int, dy: Int) = control(JSONObject().put("Cmd", "Move")
        .put("Position", JSONObject().put("x", dx).put("y", dy).put("Time", System.currentTimeMillis().toString()))
        .put("TypeOfRemote", "ProcessMouseDevice"))

    fun mouseClick() = control(JSONObject().put("Cmd", "LeftClick").put("TypeOfRemote", "ProcessMouseDevice"))

    /** Confirma o texto e fecha o teclado da TV. */
    fun textEnd() = control(JSONObject().put("Cmd", "").put("DataOfCmd", "").put("TypeOfRemote", "SendInputEnd"))

    private fun control(params: JSONObject) {
        val msg = JSONObject().put("method", "ms.remote.control").put("params", params).toString()
        if (connected) ws?.send(msg) else { pending = msg; connect() }
    }

    private fun send(k: String) = key(k)

    private fun emit(event: String, data: JSONObject?) = JSONObject().put("method", "ms.channel.emit")
        .put("params", JSONObject().put("event", event).put("to", "host").apply { data?.let { put("data", it) } })
        .toString()

    /** Abre um app na TV. Procura o ID pelo nome na lista da TV; se não achar, usa o ID salvo. */
    fun launch(name: String, id: String) {
        if (ip == null) { status("Toque em ⚙ para achar a TV no Wi-Fi primeiro"); return }
        pendingLaunch = name to id
        if (connected) runPendingLaunch() else { status("Abrindo $name pela rede…"); connect() }
    }

    @Synchronized private fun runPendingLaunch() {
        val (name, id) = pendingLaunch ?: return
        if (!connected) return
        pendingLaunch = null
        val app = findApp(name)
        val appId = app?.id ?: id.ifEmpty { null }
        if (appId == null) { status("Não achei \"$name\" na TV. Segure o atalho para escolher o app"); return }
        val type = if (app?.type == 4) "NATIVE_LAUNCH" else "DEEP_LINK"
        thread { openApp(name, appId, type) }
    }

    /** Avisado quando o app não existe na TV (404), para escolher o certo na lista. */
    @Volatile var onLaunchFailed: ((String) -> Unit)? = null

    /**
     * TVs 2021+ ignoram o "ed.apps.launch" do WebSocket; o que funciona é o REST da TV
     * (POST /api/v2/applications/<id>), que responde 200 = abriu, 404 = não instalado.
     */
    private fun openApp(name: String, appId: String, type: String) {
        val host = ip ?: return
        var code = rest("http://$host:8001/api/v2/applications/$appId")
        if (code != 200 && code != 404) code = rest("https://$host:8002/api/v2/applications/$appId")
        when (code) {
            200 -> status("Abrindo $name")
            404 -> { status("$name não está instalado nesta TV"); onLaunchFailed?.invoke(name) }
            else -> {
                // TVs mais antigas: método pelo WebSocket
                ws?.send(emit("ed.apps.launch", JSONObject().put("appId", appId).put("action_type", type)))
                status(if (code == -1) "Abrindo $name pelo método antigo (a TV não respondeu ao novo)"
                       else "A TV respondeu $code ao abrir $name; tentei o método antigo")
            }
        }
    }

    /** POST vazio; devolve o código HTTP ou -1 se não conectou. */
    private fun rest(url: String): Int = runCatching {
        client.newCall(Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build())
            .execute().use { it.code }
    }.getOrDefault(-1)

    /** Acha o app da TV pelo nome do atalho (igual ou parecido). */
    fun findApp(name: String): TvApp? {
        val n = norm(name)
        if (n.isEmpty()) return null
        return installed.firstOrNull { norm(it.name) == n }
            ?: installed.firstOrNull { norm(it.name).contains(n) || n.contains(norm(it.name)) }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Ligado: desliga. Desligado: manda Wake-on-LAN e tenta conectar. */
    fun power() {
        if (connected) { send("KEY_POWER"); return }
        val mac = prefs.getString("mac", null)
        if (mac == null) {
            status("Ligue a TV pelo controle normal uma vez com o app aberto, para eu aprender o endereço dela")
            return
        }
        status("Ligando a TV…")
        thread {
            wake(mac)
            repeat(8) {
                Thread.sleep(2500)
                if (connected) return@thread
                connect()
            }
        }
    }

    private fun wake(mac: String) = runCatching {
        val m = mac.split(':', '-').map { it.toInt(16).toByte() }
        val data = ByteArray(6) { 0xFF.toByte() } + List(16) { m }.flatten().toByteArray()
        val host = ip ?: return@runCatching
        DatagramSocket().use { s ->
            s.broadcast = true
            listOf("255.255.255.255", host.substringBeforeLast('.') + ".255", host).forEach { h ->
                repeat(3) { s.send(DatagramPacket(data, data.size, InetAddress.getByName(h), 9)) }
            }
        }
    }

    /** Modelo da TV e se ela aceita comando de voz (vem da própria TV). */
    @Volatile var model: String? = null; private set
    @Volatile var voiceSupport: Boolean? = null; private set

    private fun learnMac(host: String) {
        val dev = info(host, 3000)?.optJSONObject("device") ?: return
        model = dev.optString("modelName").ifEmpty { null }
        voiceSupport = dev.optString("VoiceSupport").takeIf { it.isNotEmpty() }?.toBoolean()
        dev.optString("wifiMac").takeIf { it.count { c -> c == ':' } == 5 }
            ?.let { prefs.edit().putString("mac", it).apply() }
    }

    private fun info(host: String, timeout: Int): JSONObject? = runCatching {
        val c = URL("http://$host:8001/api/v2/").openConnection() as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout
        c.inputStream.bufferedReader().use { JSONObject(it.readText()) }.also { c.disconnect() }
    }.getOrNull()?.takeIf { it.has("device") }

    /** Procura a TV na rede Wi-Fi (bloqueante; chamar fora da thread principal). */
    fun scan(): String? {
        val prefix = localPrefix() ?: return null
        val found = AtomicReference<String?>()
        val pool = Executors.newFixedThreadPool(64)
        for (i in 1..254) pool.execute {
            if (found.get() == null && info("$prefix.$i", 700) != null) found.compareAndSet(null, "$prefix.$i")
        }
        pool.shutdown()
        pool.awaitTermination(20, TimeUnit.SECONDS)
        return found.get()?.also { setIp(it) }
    }

    private fun localPrefix(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .sortedByDescending { it.name.startsWith("wlan") }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress?.substringBeforeLast('.')
}
