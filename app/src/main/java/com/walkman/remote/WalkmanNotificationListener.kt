package com.walkman.remote

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService

/**
 * Связь с родным плеером Sony: находит его медиа-сессию,
 * сообщает экрану о смене трека и состояния.
 */
class WalkmanNotificationListener : NotificationListenerService() {

    companion object {
        var instance: WalkmanNotificationListener? = null
        var activeController: MediaController? = null
        var onStateChanged: ((PlaybackState?) -> Unit)? = null
        var onMetadataChanged: ((MediaMetadata?) -> Unit)? = null
    }

    private val handler = Handler(Looper.getMainLooper())

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            handler.post { onStateChanged?.invoke(state) }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            handler.post { onMetadataChanged?.invoke(metadata) }
        }

        override fun onQueueChanged(queue: MutableList<android.media.session.MediaSession.QueueItem>?) {
            // Очередь поменялась: обновим счётчик "1/10" заново
            handler.post { onStateChanged?.invoke(activeController?.playbackState) }
        }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { bindActiveSession() }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        try {
            val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, WalkmanNotificationListener::class.java)
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
        } catch (e: Exception) {
        }
        bindActiveSession()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            manager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: Exception) {
        }
        activeController?.unregisterCallback(callback)
        activeController = null
        instance = null
    }

    fun bindActiveSession() {
        try {
            val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, WalkmanNotificationListener::class.java)
            val sessions = manager.getActiveSessions(component)

            val sonySession = sessions.firstOrNull {
                it.packageName.contains("sony", ignoreCase = true) ||
                    it.packageName.contains("music", ignoreCase = true)
            } ?: sessions.firstOrNull()

            if (sonySession != null && sonySession.sessionToken != activeController?.sessionToken) {
                activeController?.unregisterCallback(callback)
                activeController = sonySession
                sonySession.registerCallback(callback, handler)
                handler.post {
                    onMetadataChanged?.invoke(sonySession.metadata)
                    onStateChanged?.invoke(sonySession.playbackState)
                }
            }
        } catch (e: Exception) {
        }
    }
}
