package com.rafajames.controletv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.security.auth.x500.X500Principal
import kotlin.concurrent.thread

/**
 * Android TV / Google TV (TCL, Philco, AOC, Sony, Philips, Xiaomi, TV Box, Chromecast com Google TV).
 * Protocolo "Android TV Remote v2": TLS com certificado do celular. Na primeira vez a TV mostra
 * um código de 6 letras/números (pareamento, porta 6467); depois o controle usa a porta 6466.
 */
class AndroidTv(ctx: Context, status: (String) -> Unit) : Tv(ctx, status) {
    override val brand = "androidtv"
    override val brandName = "Android TV"

    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var sock: SSLSocket? = null
    @Volatile private var connecting = false
    @Volatile private var pending: (() -> Unit)? = null
    private val codes = LinkedBlockingQueue<String>()
    @Volatile private var imeCounter = 0
    @Volatile private var fieldCounter = 0

    /** Teclas da tela (nomes Samsung) → códigos de tecla do Android. */
    private val keys = mapOf(
        "KEY_UP" to 19, "KEY_DOWN" to 20, "KEY_LEFT" to 21, "KEY_RIGHT" to 22, "KEY_ENTER" to 23,
        "KEY_RETURN" to 4, "KEY_HOME" to 3, "KEY_MENU" to 82, "KEY_POWER" to 26,
        "KEY_VOLUP" to 24, "KEY_VOLDOWN" to 25, "KEY_MUTE" to 164, "KEY_CHUP" to 166, "KEY_CHDOWN" to 167,
        "KEY_PLAY" to 126, "KEY_PAUSE" to 127, "KEY_REWIND" to 89, "KEY_FF" to 90, "KEY_STOP" to 86,
        "KEY_SOURCE" to 178, "KEY_BT_VOICE" to 84, "KEY_INFO" to 165,
    ) + (0..9).associate { "KEY_$it" to 7 + it }

    private val paired get() = prefs.getBoolean("atvPaired_$ip", false)

    override fun connect() {
        val host = ip ?: return
        if (connected || connecting) return
        connecting = true
        state(State.CONNECTING)
        thread {
            try {
                if (!paired) pair(host)
                remote(host)
            } catch (e: Exception) {
                lastError = e.message
                if (paired && e is javax.net.ssl.SSLException) prefs.edit().remove("atvPaired_$ip").apply()
                state(State.FAILED)
                status(if (!paired) "Não consegui parear com a TV. Toque num botão para tentar de novo"
                       else "Não achei a TV. Ela está ligada e no mesmo Wi‑Fi?")
            } finally {
                connecting = false; sock = null
                if (connected) state(State.OFFLINE)
            }
        }
    }

    // ---------- Certificado do celular (gerado uma vez e guardado no Android Keystore) ----------

    private val alias = "controle-tv-atv"

    private fun sslContext(): SSLContext {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) {
            val until = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }.time
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .setRandomizedEncryptionRequired(false)
                    .setCertificateSubject(X500Principal("CN=atvremote/Controle TV"))
                    .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                    .setCertificateNotAfter(until)
                    .build())
                generateKeyPair()
            }
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, null) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(trustAll), SecureRandom()) }
    }

    private fun clientKey(): RSAPublicKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getCertificate(alias).publicKey as RSAPublicKey
    }

    private fun open(host: String, port: Int): SSLSocket {
        val s = sslContext().socketFactory.createSocket() as SSLSocket
        s.connect(InetSocketAddress(host, port), 4000)
        s.soTimeout = 0
        s.startHandshake()
        return s
    }

    // ---------- Pareamento (porta 6467) ----------

    private fun pair(host: String) {
        open(host, 6467).use { s ->
            val out = s.outputStream; val inp = s.inputStream
            fun outer(field: Int, body: Pb) = Pb().int(1, 2).int(2, 200).msg(field, body)
            fun expect(field: Int): Map<Int, List<Any>> {
                val m = Pb.parse(readFrame(inp))
                val st = (m[2]?.firstOrNull() as? Long)?.toInt() ?: 200
                if (st != 200) throw IllegalStateException("A TV recusou o pareamento ($st)")
                if (m[field] == null) throw IllegalStateException("Resposta inesperada da TV")
                return m
            }
            val enc = Pb().int(1, 3).int(2, 6)  // hexadecimal, 6 símbolos
            writeFrame(out, outer(10, Pb().str(1, "atvremote").str(2, "Controle TV")))
            expect(11)
            writeFrame(out, outer(20, Pb().msg(1, enc).int(3, 1)))
            expect(20)
            writeFrame(out, outer(30, Pb().msg(1, enc).int(2, 1)))
            expect(31)
            // A TV agora mostra o código
            codes.clear()
            status("Digite o código que apareceu na TV")
            onNeedCode?.invoke()
            val code = codes.poll(3, TimeUnit.MINUTES)?.trim()?.uppercase() ?: throw IllegalStateException("Sem código")
            if (code.length != 6) throw IllegalStateException("O código tem 6 letras/números")
            val server = (s.session.peerCertificates[0] as X509Certificate).publicKey as RSAPublicKey
            val client = clientKey()
            val md = MessageDigest.getInstance("SHA-256")
            listOf(client.modulus, client.publicExponent, server.modulus, server.publicExponent).forEach { md.update(unsigned(it)) }
            md.update(code.substring(2).chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            val secret = md.digest()
            if ((secret[0].toInt() and 0xFF) != code.substring(0, 2).toInt(16)) {
                status("Código errado. Toque num botão para tentar de novo"); throw IllegalStateException("Código errado")
            }
            writeFrame(out, outer(40, Pb().bytes(1, secret)))
            expect(41)
            prefs.edit().putBoolean("atvPaired_$ip", true).apply()
            status("Pareado! Conectando…")
        }
    }

    private fun unsigned(b: BigInteger): ByteArray = b.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }

    override fun submitCode(code: String) { codes.offer(code) }

    // ---------- Controle (porta 6466) ----------

    private val features = 1 or 2 or 4 or 32 or 64 or 512   // ping, tecla, texto, energia, volume, abrir app

    private fun remote(host: String) {
        val s = open(host, 6466)
        sock = s
        val inp = s.inputStream
        while (true) {
            val m = Pb.parse(readFrame(inp))
            when {
                m[1] != null -> {   // remote_configure
                    (m[1]!!.first() as? ByteArray)?.let { Pb.parse(it)[2]?.firstOrNull() as? ByteArray }?.let { info ->
                        val d = Pb.parse(info)
                        model = listOfNotNull((d[2]?.firstOrNull() as? ByteArray)?.decodeToString(),
                            (d[1]?.firstOrNull() as? ByteArray)?.decodeToString()).joinToString(" ").ifEmpty { null }
                    }
                    send(Pb().msg(1, Pb().int(1, features).msg(2, Pb().str(1, "Controle TV").str(2, "Rafajames")
                        .int(3, 1).str(4, "1").str(5, "atvremote").str(6, "1.0.0"))))
                }
                m[2] != null -> send(Pb().msg(2, Pb().int(1, features)))           // remote_set_active
                m[8] != null -> {                                                  // ping → responde
                    val v = (Pb.parse(m[8]!!.first() as ByteArray)[1]?.firstOrNull() as? Long)?.toInt() ?: 0
                    send(Pb().msg(9, Pb().int(1, v)))
                }
                m[40] != null -> {                                                 // remote_start: pronto
                    if (!connected) {
                        connecting = false; state(State.CONNECTED); status("Conectado à TV")
                        loadDefaultApps()
                        pending?.invoke(); pending = null
                    }
                }
                m[20] != null -> (m[20]!!.first() as? ByteArray)?.let { b ->         // teclado da TV aberto
                    val k = Pb.parse(b)
                    (k[1]?.firstOrNull() as? ByteArray)?.let { imeCounter = ((Pb.parse(it)[1]?.firstOrNull() as? Long) ?: 0L).toInt() }
                    (k[2]?.firstOrNull() as? ByteArray)?.let { fieldCounter = ((Pb.parse(it)[1]?.firstOrNull() as? Long) ?: 0L).toInt() }
                    onIme?.invoke(true)
                }
                m[21] != null -> (m[21]!!.first() as? ByteArray)?.let { b ->
                    val k = Pb.parse(b)
                    imeCounter = ((k[1]?.firstOrNull() as? Long) ?: imeCounter.toLong()).toInt()
                    fieldCounter = ((k[2]?.firstOrNull() as? Long) ?: fieldCounter.toLong()).toInt()
                }
            }
        }
    }

    private fun send(p: Pb) {
        val s = sock ?: return
        writer.execute { runCatching { writeFrame(s.outputStream, p) } }
    }

    private fun whenReady(action: () -> Unit) { if (connected) action() else { pending = action; connect() } }

    override fun key(k: String, cmd: String) {
        val code = keys[k] ?: return
        val dir = when (cmd) { "Press" -> 1; "Release" -> 2; else -> 3 }
        whenReady { send(Pb().msg(10, Pb().int(1, code).int(2, dir))) }
    }

    override fun text(t: String) = whenReady {
        val obj = Pb().int(1, t.length).int(2, t.length).str(3, t)
        send(Pb().msg(21, Pb().int(1, imeCounter).int(2, fieldCounter).msg(3, Pb().int(1, 1).msg(2, obj))))
    }

    override fun textEnd() { if (connected) send(Pb().msg(10, Pb().int(1, 66).int(2, 3))) }

    override fun refreshApps() { loadDefaultApps(); onIcon?.invoke() }

    /** A Android TV não informa os apps instalados: oferecemos os mais comuns (abrem por link). */
    private fun loadDefaultApps() {
        installed = links.map { (name, link) -> TvApp(name, link, 2, "") }
        appsSource = "lista padrão"
    }

    private val links = listOf(
        "Netflix" to "https://www.netflix.com/title", "YouTube" to "https://www.youtube.com",
        "Prime Video" to "https://app.primevideo.com", "Disney+" to "https://www.disneyplus.com",
        "Max" to "https://play.max.com", "Globoplay" to "https://globoplay.globo.com",
        "Crunchyroll" to "crunchyroll://", "Spotify" to "spotify://", "Twitch" to "twitch://home",
        "Apple TV" to "https://tv.apple.com", "Paramount+" to "https://www.paramountplus.com",
        "Pluto TV" to "https://pluto.tv", "Plex" to "plex://", "YouTube Music" to "https://music.youtube.com",
    )

    override fun launch(name: String, id: String) {
        if (ip == null) { status("Toque em ⚙ para escolher a TV primeiro"); return }
        val link = id.takeIf { it.contains("://") } ?: findApp(name)?.id
            ?: links.firstOrNull { norm(name).contains(norm(it.first)) || norm(it.first).contains(norm(name)) }?.second
        if (link == null) { status("Não sei abrir \"$name\" nesta TV"); onLaunchFailed?.invoke(name); return }
        whenReady { status("Abrindo $name"); send(Pb().msg(90, Pb().str(1, link))) }
    }

    override fun power() {
        if (connected) send(Pb().msg(10, Pb().int(1, 26).int(2, 3)))
        else { status("Ligando…"); whenReady { send(Pb().msg(10, Pb().int(1, 26).int(2, 3))) } }
    }

    override fun disconnect() {
        runCatching { sock?.close() }; sock = null
        connecting = false
        state(State.OFFLINE)
    }

    // ---------- Mensagens: protobuf mínimo, cada uma precedida pelo tamanho ----------

    private fun writeFrame(out: OutputStream, p: Pb) {
        val body = p.bytes(); val frame = ByteArrayOutputStream()
        Pb.varint(frame, body.size.toLong()); frame.write(body)
        synchronized(out) { out.write(frame.toByteArray()); out.flush() }
    }

    private fun readFrame(inp: InputStream): ByteArray {
        var len = 0L; var shift = 0
        while (true) {
            val b = inp.read(); if (b < 0) throw java.io.EOFException("A TV fechou a conexão")
            len = len or ((b and 0x7F).toLong() shl shift); shift += 7
            if (b and 0x80 == 0) break
        }
        val buf = ByteArray(len.toInt()); var off = 0
        while (off < buf.size) { val n = inp.read(buf, off, buf.size - off); if (n < 0) throw java.io.EOFException(); off += n }
        return buf
    }
}

/** Codificador/decodificador protobuf bem simples (só o que o protocolo usa). */
class Pb {
    private val out = ByteArrayOutputStream()
    private fun tag(field: Int, type: Int) = varint(out, ((field shl 3) or type).toLong())
    fun int(field: Int, v: Int) = apply { tag(field, 0); varint(out, v.toLong() and 0xFFFFFFFFL) }
    fun bytes(field: Int, b: ByteArray) = apply { tag(field, 2); varint(out, b.size.toLong()); out.write(b) }
    fun str(field: Int, s: String) = bytes(field, s.toByteArray())
    fun msg(field: Int, p: Pb) = bytes(field, p.bytes())
    fun bytes(): ByteArray = out.toByteArray()

    companion object {
        fun varint(o: ByteArrayOutputStream, value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) { o.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
            o.write(v.toInt())
        }

        /** Campo → valores (Long para números, ByteArray para textos e mensagens). */
        fun parse(b: ByteArray): Map<Int, List<Any>> {
            val m = HashMap<Int, MutableList<Any>>()
            var i = 0
            fun readVar(): Long { var r = 0L; var s = 0; while (true) { val x = b[i++].toInt() and 0xFF; r = r or ((x and 0x7F).toLong() shl s); s += 7; if (x and 0x80 == 0) return r } }
            while (i < b.size) {
                val key = readVar(); val field = (key ushr 3).toInt()
                val v: Any = when ((key and 7).toInt()) {
                    0 -> readVar()
                    2 -> { val n = readVar().toInt(); b.copyOfRange(i, i + n).also { i += n } }
                    5 -> { i += 4; 0L }
                    1 -> { i += 8; 0L }
                    else -> return m
                }
                m.getOrPut(field) { mutableListOf() }.add(v)
            }
            return m
        }
    }
}
