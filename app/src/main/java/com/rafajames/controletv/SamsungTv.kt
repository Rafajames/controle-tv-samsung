package com.rafajames.controletv

import android.content.Context
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
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

    private val prefs = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)
    val ip: String? get() = prefs.getString("ip", null)

    @Volatile var connected = false; private set
    @Volatile private var connecting = false
    @Volatile private var pending: String? = null
    @Volatile private var pendingLaunch: Pair<String, String>? = null
    /** Apps instalados na TV: nome, appId, tipo */
    @Volatile var installed: List<Triple<String, String, Int>> = emptyList(); private set
    private var ws: WebSocket? = null

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
                            Triple(a.optString("name"), a.optString("appId"), a.optInt("app_type", 2))
                        }.filter { it.second.isNotEmpty() }.sortedBy { it.first.lowercase() }
                        runPendingLaunch()
                    }
                    "ms.channel.unauthorized" -> {
                        connecting = false
                        status("A TV recusou. Toque num botão e aceite o aviso na TV")
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket != ws) return
                connected = false; connecting = false
                status("Sem conexão com a TV ($host). Toque em ⚙ para procurar")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket != ws) return
                connected = false; connecting = false
            }
        })
    }

    fun disconnect() {
        ws?.close(1000, null); ws = null
        connected = false; connecting = false
    }

    fun key(k: String) {
        val params = JSONObject()
            .put("Cmd", "Click").put("DataOfCmd", k)
            .put("Option", "false").put("TypeOfRemote", "SendRemoteKey")
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
        val n = norm(name)
        val app = installed.firstOrNull { norm(it.first) == n }
            ?: installed.firstOrNull { norm(it.first).contains(n) || n.contains(norm(it.first)) }
        val appId = app?.second ?: id.ifEmpty { null }
        if (appId == null) { status("Não achei \"$name\" na TV. Segure o atalho para escolher o app"); return }
        val type = if (app?.third == 4) "NATIVE_LAUNCH" else "DEEP_LINK"
        ws?.send(emit("ed.apps.launch", JSONObject().put("appId", appId).put("action_type", type)))
        status("Abrindo $name")
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

    private fun learnMac(host: String) {
        info(host, 3000)?.optJSONObject("device")?.optString("wifiMac")
            ?.takeIf { it.count { c -> c == ':' } == 5 }
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
