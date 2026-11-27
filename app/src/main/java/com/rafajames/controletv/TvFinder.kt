package com.rafajames.controletv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Procura TVs no Wi‑Fi: SSDP (Samsung, LG, Roku), mDNS (Android TV / Google TV)
 * e, por garantia, a varredura antiga da Samsung (porta 8001).
 */
object TvFinder {
    data class Found(val brand: String, val ip: String, val name: String)

    /** Bloqueia uns 5 segundos; chamar fora da thread principal. */
    fun find(ctx: Context): List<Found> {
        val found = ConcurrentHashMap<String, Found>()   // chave: ip
        fun add(f: Found) { found.merge(f.ip, f) { old, new -> if (old.name.isBlank() || old.name == old.ip) new else old } }
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("controle-tv").apply { setReferenceCounted(false); runCatching { acquire() } }
        try {
            val jobs = listOf(
                thread { runCatching { ssdp(::add) } },
                thread { runCatching { nsd(ctx, ::add) } },
                thread { runCatching { samsungSweep(::add) } },
            )
            jobs.forEach { it.join(9000) }
        } finally { runCatching { lock.release() } }
        return found.values.sortedWith(compareBy({ it.brand }, { it.name }))
    }

    // ---------- SSDP ----------

    private fun ssdp(add: (Found) -> Unit) {
        val targets = listOf("ssdp:all", "roku:ecp", "urn:lge-com:service:webos-second-screen:1",
            "urn:samsung.com:device:RemoteControlReceiver:1", "urn:dial-multiscreen-org:service:dial:1")
        val seen = HashSet<String>()
        MulticastSocket(null).use { s ->
            s.reuseAddress = true; s.bind(InetSocketAddress(0)); s.soTimeout = 400
            val group = InetAddress.getByName("239.255.255.250")
            repeat(2) {
                targets.forEach { st ->
                    val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n".toByteArray()
                    s.send(DatagramPacket(msg, msg.size, group, 1900))
                }
            }
            val end = System.currentTimeMillis() + 3500
            val buf = ByteArray(2048)
            val pool = Executors.newFixedThreadPool(8)
            while (System.currentTimeMillis() < end) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: SocketTimeoutException) { continue }
                val ip = p.address.hostAddress ?: continue
                val text = String(p.data, 0, p.length)
                val low = text.lowercase()
                val brand = when {
                    "roku" in low -> "roku"
                    "webos" in low || "lge-com" in low || "lg smart tv" in low -> "lg"
                    "samsung" in low -> "samsung"
                    else -> null
                } ?: continue
                if (!seen.add("$brand$ip")) continue
                val location = Regex("(?im)^location:\\s*(\\S+)").find(text)?.groupValues?.get(1)
                pool.execute {
                    when (brand) {
                        // Samsung responde SSDP também em celulares e caixas de som: confirma que é TV
                        "samsung" -> samsungInfo(ip, 1500)?.let { add(Found("samsung", ip, it)) }
                        else -> add(Found(brand, ip, location?.let { friendlyName(it) } ?: Tv.brands[brand]!!))
                    }
                }
            }
            pool.shutdown(); pool.awaitTermination(3, TimeUnit.SECONDS)
        }
    }

    private fun friendlyName(location: String): String? = runCatching {
        val c = URL(location).openConnection() as HttpURLConnection
        c.connectTimeout = 1500; c.readTimeout = 1500
        val xml = c.inputStream.bufferedReader().use { it.readText() }
        Regex("<friendlyName>([^<]+)</friendlyName>").find(xml)?.groupValues?.get(1)?.replace("&amp;", "&")
    }.getOrNull()

    /** Nome da TV Samsung (ou null se não for uma TV Samsung). */
    private fun samsungInfo(ip: String, timeout: Int): String? = runCatching {
        val c = URL("http://$ip:8001/api/v2/").openConnection() as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout
        val dev = c.inputStream.bufferedReader().use { JSONObject(it.readText()) }.getJSONObject("device")
        if (!dev.optString("type").contains("TV", true)) return null
        dev.optString("name").ifEmpty { dev.optString("modelName") }.replace("[TV] ", "").ifEmpty { "TV Samsung" }
    }.getOrNull()

    // ---------- mDNS: Android TV / Google TV ----------

    private fun nsd(ctx: Context, add: (Found) -> Unit) {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val queue = java.util.concurrent.LinkedBlockingQueue<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { queue.offer(info) }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
        nsd.discoverServices("_androidtvremote2._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        val end = System.currentTimeMillis() + 4500
        while (System.currentTimeMillis() < end) {
            val info = queue.poll(300, TimeUnit.MILLISECONDS) ?: continue
            val done = java.util.concurrent.CountDownLatch(1)
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, code: Int) { done.countDown() }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    i.host?.hostAddress?.let { add(Found("androidtv", it, i.serviceName)) }
                    done.countDown()
                }
            })
            done.await(2, TimeUnit.SECONDS)
        }
        runCatching { nsd.stopServiceDiscovery(listener) }
    }

    // ---------- Varredura Samsung (a de sempre, porta 8001) ----------

    private fun samsungSweep(add: (Found) -> Unit) {
        val prefix = localPrefix() ?: return
        val pool = Executors.newFixedThreadPool(64)
        for (i in 1..254) pool.execute { samsungInfo("$prefix.$i", 700)?.let { add(Found("samsung", "$prefix.$i", it)) } }
        pool.shutdown(); pool.awaitTermination(8, TimeUnit.SECONDS)
    }

    private fun localPrefix(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .sortedByDescending { it.name.startsWith("wlan") }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress?.substringBeforeLast('.')

    /** IP digitado à mão: descobre a marca pelas portas que a TV responde. */
    fun identify(ip: String): Found? {
        samsungInfo(ip, 1500)?.let { return Found("samsung", ip, it) }
        runCatching {
            val c = URL("http://$ip:8060/query/device-info").openConnection() as HttpURLConnection
            c.connectTimeout = 1500; c.readTimeout = 1500
            if (c.responseCode == 200) return Found("roku", ip, "Roku TV")
        }
        if (open(ip, 3001) || open(ip, 3000)) return Found("lg", ip, "TV LG")
        if (open(ip, 6466) || open(ip, 6467)) return Found("androidtv", ip, "Android TV")
        return null
    }

    private fun open(ip: String, port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress(ip, port), 1200) }; true }.getOrDefault(false)
}
