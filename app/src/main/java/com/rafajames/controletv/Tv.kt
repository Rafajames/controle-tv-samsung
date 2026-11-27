package com.rafajames.controletv

import android.content.Context
import okhttp3.OkHttpClient
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Uma TV na rede. Cada marca tem seu "tradutor" (SamsungTv, LgTv, RokuTv, AndroidTv);
 * a tela fala sempre com os nomes de tecla da Samsung (KEY_UP, KEY_VOLUP…) e cada marca traduz.
 */
abstract class Tv(ctx: Context, protected val status: (String) -> Unit) {

    enum class State { CONNECTING, CONNECTED, FAILED, OFFLINE }
    /** App instalado na TV. icon = caminho/endereço do ícone na TV. */
    data class TvApp(val name: String, val id: String, val type: Int, val icon: String)

    abstract val brand: String
    abstract val brandName: String

    @Volatile var onState: ((State) -> Unit)? = null
    @Volatile var onIcon: (() -> Unit)? = null
    @Volatile var onIme: ((Boolean) -> Unit)? = null
    @Volatile var onLaunchFailed: ((String) -> Unit)? = null
    @Volatile var onResolved: ((String, String) -> Unit)? = null
    /** Android TV: a TV mostrou um código de 6 letras e o app precisa pedir ao usuário (responder com submitCode). */
    @Volatile var onNeedCode: (() -> Unit)? = null

    protected val prefs = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)
    private val iconDir = File(ctx.filesDir, "icons").apply { mkdirs() }
    fun iconFile(appId: String) = File(iconDir, brand + "_" + appId.filter { it.isLetterOrDigit() || it == '.' } + ".png")

    open val ip: String? get() = prefs.getString("ip", null)
    /** Nome que a TV anunciou na rede (ex.: "Sala"), se houver. */
    val tvName: String? get() = prefs.getString("tvName", null)

    @Volatile var connected = false; protected set
    @Volatile var installed: List<TvApp> = emptyList(); protected set
    /** De onde veio a lista de apps: "TV", "sondagem", "lista padrão" ou "nenhuma". */
    @Volatile var appsSource = "nenhuma"; protected set
    @Volatile var lastError: String? = null; protected set
    @Volatile var model: String? = null; protected set
    @Volatile var voiceSupport: Boolean? = null; protected set

    abstract fun connect()
    abstract fun disconnect()
    /** cmd: "Click" (apertar e soltar), "Press" (segurar) ou "Release" (soltar). */
    abstract fun key(k: String, cmd: String = "Click")
    abstract fun text(t: String)
    open fun textEnd() {}
    open fun mouseMove(dx: Int, dy: Int) {}
    open fun mouseClick() { key("KEY_ENTER") }
    open val hasMouse = false
    abstract fun launch(name: String, id: String)
    abstract fun refreshApps()
    abstract fun power()
    open fun submitCode(code: String) {}
    /** Procura a TV desta marca de novo na rede (o roteador pode ter trocado o IP). */
    open fun scan(): String? = null

    open fun setIp(newIp: String) {
        if (newIp != ip) prefs.edit().putString("ip", newIp).apply()
        disconnect()
    }

    protected fun state(s: State) {
        if (s == State.CONNECTED) connected = true
        if (s == State.FAILED || s == State.OFFLINE) connected = false
        onState?.invoke(s)
    }

    /** Acha o app da TV pelo nome do atalho (igual ou parecido). */
    fun findApp(name: String): TvApp? {
        val n = norm(name)
        if (n.isEmpty()) return null
        return installed.firstOrNull { norm(it.name) == n }
            ?: installed.firstOrNull { norm(it.name).contains(n) || n.contains(norm(it.name)) }
    }

    protected fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Wake-on-LAN: liga TVs que aceitam "ligar pela rede". */
    protected fun wake(mac: String) = runCatching {
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

    companion object {
        /** TVs usam certificado próprio: aceitamos qualquer um (só falamos com aparelhos da rede local). */
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        }
        val lanClient: OkHttpClient by lazy {
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
            OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trustAll).hostnameVerifier { _, _ -> true }
                .connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS).build()
        }

        val brands = mapOf("samsung" to "Samsung", "lg" to "LG", "roku" to "Roku", "androidtv" to "Android TV / Google TV")

        /** Cria o tradutor da TV escolhida (Samsung se nada foi escolhido ainda). */
        fun create(ctx: Context, status: (String) -> Unit): Tv {
            val c = ctx.applicationContext
            return when (c.getSharedPreferences("tv", Context.MODE_PRIVATE).getString("brand", "samsung")) {
                "lg" -> LgTv(c, status)
                "roku" -> RokuTv(c, status)
                "androidtv" -> AndroidTv(c, status)
                else -> SamsungTv(c, status)
            }
        }
    }
}
