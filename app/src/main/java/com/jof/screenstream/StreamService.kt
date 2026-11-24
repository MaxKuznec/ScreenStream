package com.jof.screenstream

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import com.pedro.common.ConnectChecker
import com.pedro.library.base.DisplayBase

class StreamService : Service(), ConnectChecker {
    private var stream: DisplayBase? = null
    private var projection: MediaProjection? = null
    private var overlay: Overlay? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i == null || i.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        val p = Prefs.get(this)
        val url = p.getString(Prefs.URL, "")!!.trimEnd('/') + "/" + p.getString(Prefs.KEY, "")
        createChannel()
        val notif = notification("Подключение…")
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, notif)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(i.getIntExtra("code", 0), i.getParcelableExtra<Intent>("data")!!)
        val s = DisplayBase(this, this)
        stream = s
        val ok = s.prepareVideo(p.getInt(Prefs.W, 720), p.getInt(Prefs.H, 1280),
                                p.getInt(Prefs.FPS, 60), p.getInt(Prefs.BITRATE, 6000) * 1000, 0)
                 && s.prepareAudio(64_000, 44100, true, false, false)
        if (!ok) {
            toast("Энкодер не поддерживает параметры. Снизьте FPS или разрешение.")
            stopSelf(); return START_NOT_STICKY
        }
        s.startStream(projection!!, url)
        overlay = Overlay(this,
            onStart = {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("autostart", true))
                stopSelf()
            },
            onStop = { stopSelf() })
        overlay!!.show()
        overlay!!.collapse()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stream?.stopStream()
        projection?.stop()
        overlay?.remove()
        super.onDestroy()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("stream", "Stream", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(this, 0,
            Intent(this, StreamService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "stream")
            .setContentTitle("ScreenStream").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(Notification.Action.Builder(null, "Стоп", stop).build())
            .setOngoing(true).build()
    }

    private fun toast(t: String) = handler.post { Toast.makeText(this, t, Toast.LENGTH_SHORT).show() }
    private fun update(t: String) = handler.post {
        getSystemService(NotificationManager::class.java).notify(1, notification(t))
    }

    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() { toast("Трансляция запущена"); update("В эфире") }
    override fun onConnectionFailed(reason: String) { toast("Ошибка: $reason"); stopSelf() }
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() { toast("Соединение разорвано"); stopSelf() }
    override fun onAuthError() { toast("Неверный ключ трансляции"); stopSelf() }
    override fun onAuthSuccess() {}
}

class Overlay(private val ctx: Context,
              private val onStart: () -> Unit,
              private val onStop: () -> Unit) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val p = Prefs.get(ctx)
    private var root: LinearLayout? = null
    private var panel: LinearLayout? = null
    private var header: Button? = null
    private fun px(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()

    fun show() {
        if (root != null) return
        val fpsBtn = Button(ctx)
        val resBtn = Button(ctx)
        val brBtn = Button(ctx)
        fun labels() {
            fpsBtn.text = "FPS: ${p.getInt(Prefs.FPS, 60)}"
            resBtn.text = "Экран: ${p.getInt(Prefs.W, 720)}×${p.getInt(Prefs.H, 1280)}"
            brBtn.text = "Битрейт: ${p.getInt(Prefs.BITRATE, 6000)} кбит/с"
        }
        fpsBtn.setOnClickListener {
            p.edit().putInt(Prefs.FPS, if (p.getInt(Prefs.FPS, 60) == 60) 90 else 60).apply()
            labels(); toast("Применится при следующем запуске")
        }
        resBtn.setOnClickListener {
            val hi = p.getInt(Prefs.W, 720) == 1080
            p.edit().putInt(Prefs.W, if (hi) 720 else 1080)
                    .putInt(Prefs.H, if (hi) 1280 else 1920).apply()
            labels(); toast("Применится при следующем запуске")
        }
        brBtn.setOnClickListener {
            val next = when (p.getInt(Prefs.BITRATE, 6000)) { 4000 -> 6000; 6000 -> 8000; else -> 4000 }
            p.edit().putInt(Prefs.BITRATE, next).apply()
            labels(); toast("Применится при следующем запуске")
        }
        labels()
        panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        panel!!.addView(Button(ctx).apply { text = "Старт"; setOnClickListener { onStart() } })
        panel!!.addView(Button(ctx).apply { text = "Стоп"; setOnClickListener { onStop() } })
        panel!!.addView(fpsBtn); panel!!.addView(resBtn); panel!!.addView(brBtn)
        header = Button(ctx).apply { text = "● Stream"; setOnClickListener { toggle() } }
        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC1E1E1E.toInt())
            val pad = px(8)
            setPadding(pad, pad, pad, pad)
            addView(header); addView(panel)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = px(8); lp.y = px(120)
        wm.addView(root, lp)
    }

    fun collapse() { panel?.visibility = View.GONE; header?.text = "●" }

    private fun toggle() {
        if (panel?.visibility == View.GONE) {
            panel?.visibility = View.VISIBLE; header?.text = "● Stream"
        } else collapse()
    }

    fun remove() { root?.let { wm.removeView(it) }; root = null }
}
