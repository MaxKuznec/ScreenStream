package com.jof.screenstream

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    private lateinit var mpm: MediaProjectionManager

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        mpm = getSystemService(MediaProjectionManager::class.java)
        val p = Prefs.get(this)
        findViewById<EditText>(R.id.url).setText(p.getString(Prefs.URL, "rtmp://a.rtmp.youtube.com/live2"))
        findViewById<EditText>(R.id.key).setText(p.getString(Prefs.KEY, ""))
        findViewById<Spinner>(R.id.res).setSelection(if (p.getInt(Prefs.W, 720) == 1080) 1 else 0)
        findViewById<Spinner>(R.id.fps).setSelection(if (p.getInt(Prefs.FPS, 60) == 90) 1 else 0)
        findViewById<EditText>(R.id.bitrate).setText(p.getInt(Prefs.BITRATE, 6000).toString())
        findViewById<Button>(R.id.overlayPerm).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.start).setOnClickListener { launch() }
        if (intent.getBooleanExtra("autostart", false)) launch()
    }

    private fun launch() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Сначала выдайте разрешение на оверлей", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != 0) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val p = Prefs.get(this).edit()
        p.putString(Prefs.URL, findViewById<EditText>(R.id.url).text.toString().trim())
        p.putString(Prefs.KEY, findViewById<EditText>(R.id.key).text.toString().trim())
        val hiRes = findViewById<Spinner>(R.id.res).selectedItemPosition == 1
        p.putInt(Prefs.W, if (hiRes) 1080 else 720)
        p.putInt(Prefs.H, if (hiRes) 1920 else 1280)
        p.putInt(Prefs.FPS, if (findViewById<Spinner>(R.id.fps).selectedItemPosition == 1) 90 else 60)
        p.putInt(Prefs.BITRATE, findViewById<EditText>(R.id.bitrate).text.toString().toIntOrNull() ?: 6000)
        p.apply()
        startActivityForResult(mpm.createScreenCaptureIntent(), 100)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 100 && res == RESULT_OK && data != null) {
            startForegroundService(Intent(this, StreamService::class.java)
                .putExtra("code", res).putExtra("data", data))
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, "Захват экрана отклонён", Toast.LENGTH_SHORT).show()
        }
    }
}

object Prefs {
    const val URL = "url"; const val KEY = "key"
    const val W = "w"; const val H = "h"
    const val FPS = "fps"; const val BITRATE = "bitrate"
    fun get(c: Context): SharedPreferences =
        c.getSharedPreferences("stream", Context.MODE_PRIVATE)
}
