package app.localcart.notify

import ai.nexconn.chat.message.Message
import ai.nexconn.chat.message.model.MessageDirection
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.localcart.MainActivity
import app.localcart.R
import app.localcart.chat.ChatEvent
import app.localcart.chat.NexconnChat
import app.localcart.data.repo.PeerDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class InAppBanner(val id: Long, val peerId: String, val title: String, val body: String)

/**
 * Real-time notifications without FCM: while the app process is alive the Nexconn connection stays up,
 * so incoming messages show an in-app banner in the foreground and a local notification when the app
 * is in the background. (Push when the process is dead needs FCM on the console; see README.)
 */
class Notifier(
    private val context: Context,
    private val chat: NexconnChat,
    private val peers: PeerDirectory,
    scope: CoroutineScope,
) {
    private val _banner = MutableStateFlow<InAppBanner?>(null)
    val banner: StateFlow<InAppBanner?> = _banner.asStateFlow()

    @Volatile private var foreground = false

    init {
        createChannel()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foreground = true
            }

            override fun onStop(owner: LifecycleOwner) {
                foreground = false
            }
        })
        scope.launch {
            chat.events.collect { event ->
                if (event is ChatEvent.Received) onMessage(event.message)
            }
        }
    }

    fun dismissBanner() {
        _banner.value = null
    }

    private suspend fun onMessage(message: Message) {
        if (message.direction != MessageDirection.RECEIVE) return
        // Old messages synced on reconnect should not ring.
        if (System.currentTimeMillis() - message.sentTime > 2 * 60_000) return
        val peerId = message.channelIdentifier.channelId
        if (foreground && chat.activePeer == peerId) return
        val peer = peers.get(peerId) ?: runCatching { peers.refresh()[peerId] }.getOrNull()
        val title = peer?.name ?: "New message"
        val body = NexconnChat.preview(message)
        if (foreground) {
            _banner.value = InAppBanner(System.nanoTime(), peerId, title, body)
        } else {
            postSystemNotification(peerId, title, body)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_messages), NotificationManager.IMPORTANCE_HIGH)
        channel.description = context.getString(R.string.notification_channel_messages_desc)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun postSystemNotification(peerId: String, title: String, body: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_CHAT, peerId)
        }
        val pending = PendingIntent.getActivity(context, peerId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(peerId.hashCode(), notification) }
    }

    companion object {
        const val CHANNEL_ID = "messages"
    }
}
