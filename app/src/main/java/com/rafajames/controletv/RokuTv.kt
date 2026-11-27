package com.rafajames.controletv

import android.content.Context
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Roku TV (AOC, TCL, Philco Roku…): comandos HTTP na porta 8060, sem precisar autorizar. */
class RokuTv(ctx: Context, status: (String) -> Unit) : Tv(ctx, status) {
    override val brand = "roku"
    override val brandName = "Roku"
    private val worker = Executors.newSingleThreadExecutor()
    private val base get() = "http://$ip:8060"

    private val keys = mapOf(
        "KEY_UP" to "Up", "KEY_DOWN" to "Down", "KEY_LEFT" to "Left", "KEY_RIGHT" to "Right",
        "KEY_ENTER" to "Select", "KEY_RETURN" to "Back", "KEY_HOME" to "Home", "KEY_MENU" to "Info",
        "KEY_VOLUP" to "VolumeUp", "KEY_VOLDOWN" to "VolumeDown", "KEY_MUTE" to "VolumeMute",
        "KEY_CHUP" to "ChannelUp", "KEY_CHDOWN" to "ChannelDown",
        "KEY_PLAY" to "Play", "KEY_PAUSE" to "Play", "KEY_REWIND" to "Rev", "KEY_FF" to "Fwd",
        "KEY_SOURCE" to "InputHDMI1", "KEY_BT_VOICE" to "Search",
    ) + (0..9).associate { "KEY_$it" to "Lit_$it" }

    private fun post(path: String): Int = runCatching {
        lanClient.newCall(Request.Builder().url(base + path).post(ByteArray(0).toRequestBody()).build()).execute().use { it.code }
    }.getOrDefault(-1)

    private fun get(path: String): String? = runCatching {
        lanClient.newCall(Request.Builder().url(base + path).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    override fun connect() {
        if (ip == null || connected) return
        state(State.CONNECTING)
        thread {
            val info = get("/query/device-info")
            if (info == null) { state(State.FAILED); status("Não achei a Roku TV. Ela está ligada e no mesmo Wi‑Fi?"); return@thread }
            model = tag(info, "user-device-name") ?: tag(info, "model-name")
            state(State.CONNECTED)
            refreshApps()
        }
    }

    private fun tag(xml: String, name: String) = Regex("<$name>([^<]*)</$name>").find(xml)?.groupValues?.get(1)?.ifEmpty { null }

    override fun disconnect() { connected = false; state(State.OFFLINE) }

    override fun key(k: String, cmd: String) {
        if (k == "KEY_POWER") { power(); return }
        val name = keys[k] ?: return
        val path = when (cmd) { "Press" -> "/keydown/$name"; "Release" -> "/keyup/$name"; else -> "/keypress/$name" }
        worker.execute { if (post(path) == -1) { state(State.FAILED) } else if (!connected) state(State.CONNECTED) }
    }

    override fun text(t: String) = worker.execute {
        t.forEach { c -> post("/keypress/Lit_" + URLEncoder.encode(c.toString(), "UTF-8")) }
    }

    override fun textEnd() { worker.execute { post("/keypress/Enter") } }

    override fun refreshApps() {
        thread {
            val xml = get("/query/apps") ?: return@thread
            installed = Regex("<app id=\"([^\"]+)\"[^>]*>([^<]*)</app>").findAll(xml)
                .map { TvApp(it.groupValues[2].replace("&amp;", "&"), it.groupValues[1], 2, "/query/icon/" + it.groupValues[1]) }
                .toList().sortedBy { it.name.lowercase() }
            appsSource = "TV"
            onIcon?.invoke()
            installed.filter { !iconFile(it.id).exists() }.forEach { a ->
                runCatching {
                    lanClient.newCall(Request.Builder().url(base + a.icon).build()).execute().use { r ->
                        if (r.isSuccessful) r.body?.bytes()?.let { iconFile(a.id).writeBytes(it) }
                    }
                }
            }
            onIcon?.invoke()
        }
    }

    override fun launch(name: String, id: String) {
        if (ip == null) { status("Toque em ⚙ para escolher a TV primeiro"); return }
        val appId = findApp(name)?.id ?: id.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
            ?: known.entries.firstOrNull { norm(name).contains(it.key) }?.value
        if (appId == null) { status("Não achei \"$name\" na Roku"); onLaunchFailed?.invoke(name); return }
        worker.execute {
            when (post("/launch/$appId")) {
                200, 204 -> status("Abrindo $name")
                404 -> { status("$name não está instalado nesta Roku"); onLaunchFailed?.invoke(name) }
                else -> status("A Roku não respondeu")
            }
        }
    }

    private val known = mapOf("netflix" to "12", "youtube" to "837", "prime" to "13", "disney" to "291097",
        "globoplay" to "172386", "spotify" to "22297", "appletv" to "551012")

    override fun power() {
        worker.execute {
            if (post("/keypress/Power") == -1) {
                status("A Roku não respondeu. Ative em Configurações › Sistema › Energia: \"Ligar rápido\"")
            }
        }
    }
}
