package com.walkman.remote

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Физические кнопки громкости управляют именно громкостью музыки
        volumeControlStream = AudioManager.STREAM_MUSIC

        setContentView(WalkmanRemoteView(this))
        checkNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        WalkmanNotificationListener.instance?.bindActiveSession()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    private fun hideSystemUI() {
        try {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        } catch (e: Exception) {
        }
    }

    // Чтобы читать трек из плеера Sony, приложению нужен доступ к уведомлениям.
    private fun checkNotificationPermission() {
        try {
            val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            val mine = ComponentName(this, WalkmanNotificationListener::class.java).flattenToString()
            if (enabled == null || !enabled.contains(mine)) {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
        } catch (e: Exception) {
        }
    }
}
