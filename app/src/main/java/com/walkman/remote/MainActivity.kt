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

    private var remoteView: WalkmanRemoteView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Физические кнопки громкости управляют именно громкостью музыки
        volumeControlStream = AudioManager.STREAM_MUSIC

        val view = WalkmanRemoteView(this)
        remoteView = view
        setContentView(view)
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

    // Кнопка "назад" сначала закрывает список треков
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (remoteView?.handleBack() == true) return
        super.onBackPressed()
    }

    // Ответ на запрос доступа к музыкальным файлам (для времени и формата треков)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        remoteView?.onPermissionResult()
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
