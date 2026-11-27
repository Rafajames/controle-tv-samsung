package com.rafajames.controletv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlin.concurrent.thread

/** Recebe os toques do widget e da notificação e manda a tecla para a TV (sem abrir o app). */
class RemoteReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        var key = intent.getStringExtra(EXTRA_KEY) ?: return
        val prefs = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)
        if (key == PLAY_PAUSE) {
            val playing = prefs.getBoolean("playing", true)
            key = if (playing) "KEY_PAUSE" else "KEY_PLAY"
            prefs.edit().putBoolean("playing", !playing).apply()
        }
        val ir = IrRemote(ctx)
        val pending = goAsync()
        thread {
            try {
                if (ir.available && prefs.getBoolean("ir", true)) {
                    ir.send(key); Thread.sleep(400)
                } else {
                    val tv = Tv.create(ctx.applicationContext) { }
                    if (tv.ip == null) return@thread
                    tv.connect()
                    repeat(30) { if (!tv.connected) Thread.sleep(100) }
                    if (key == "KEY_POWER") tv.power() else tv.key(key)
                    Thread.sleep(if (key == "KEY_POWER" && !tv.connected) 20000 else 1500)
                    tv.disconnect()
                }
            } finally { pending.finish() }
        }
    }

    companion object {
        const val EXTRA_KEY = "key"
        const val PLAY_PAUSE = "PLAY_PAUSE"
        private const val CHANNEL = "controle"
        const val NOTIF_ID = 1

        fun intent(ctx: Context, key: String, code: Int): PendingIntent = PendingIntent.getBroadcast(ctx, code,
            Intent(ctx, RemoteReceiver::class.java).putExtra(EXTRA_KEY, key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        /** Notificação com volume e play/pause (aparece também na tela de bloqueio). */
        fun showNotification(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Controle da TV", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_ir)
                .setContentTitle("Controle TV")
                .setContentText("Volume e pausa sem abrir o app")
                .setContentIntent(open)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .addAction(Notification.Action.Builder(null, "Vol −", intent(ctx, "KEY_VOLDOWN", 11)).build())
                .addAction(Notification.Action.Builder(null, "Vol +", intent(ctx, "KEY_VOLUP", 12)).build())
                .addAction(Notification.Action.Builder(null, "Pausar / Play", intent(ctx, PLAY_PAUSE, 13)).build())
                .build()
            runCatching { nm.notify(NOTIF_ID, n) }
        }

        fun hideNotification(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }
}

/** Widget da tela inicial: liga/desliga, volume, mudo e play/pause. */
class WidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val v = RemoteViews(ctx.packageName, R.layout.widget).apply {
            setOnClickPendingIntent(R.id.wPower, RemoteReceiver.intent(ctx, "KEY_POWER", 21))
            setOnClickPendingIntent(R.id.wVolDown, RemoteReceiver.intent(ctx, "KEY_VOLDOWN", 22))
            setOnClickPendingIntent(R.id.wMute, RemoteReceiver.intent(ctx, "KEY_MUTE", 23))
            setOnClickPendingIntent(R.id.wVolUp, RemoteReceiver.intent(ctx, "KEY_VOLUP", 24))
            setOnClickPendingIntent(R.id.wPlay, RemoteReceiver.intent(ctx, RemoteReceiver.PLAY_PAUSE, 25))
            setOnClickPendingIntent(R.id.wOpen, PendingIntent.getActivity(ctx, 26,
                Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        }
        mgr.updateAppWidget(ids, v)
    }

    companion object {
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, WidgetProvider::class.java))
            if (ids.isNotEmpty()) WidgetProvider().onUpdate(ctx, mgr, ids)
        }
    }
}
