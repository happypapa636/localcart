package app.localcart.core

import android.content.Context
import app.localcart.chat.NexconnChat
import app.localcart.data.api.ApiClient
import app.localcart.data.repo.AuthRepository
import app.localcart.data.repo.CatalogRepository
import app.localcart.data.repo.OrderRepository
import app.localcart.data.repo.PeerDirectory
import app.localcart.data.repo.SessionRepository
import app.localcart.data.repo.ShopRepository
import app.localcart.media.LocationClient
import app.localcart.media.VoicePlayer
import app.localcart.media.VoiceRecorder
import app.localcart.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency graph, created once in [app.localcart.LocalCartApp]. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val session = SessionRepository(context)

    private val clients = ApiClient.create(
        tokenProvider = { session.token() },
        // A 401 on a signed-in request means the session expired or the account is gone.
        onUnauthorized = { appScope.launch { if (session.currentUser != null) signOut() } },
    )
    val api = clients.first
    val httpClient = clients.second

    val auth = AuthRepository(api, session)
    val shops = ShopRepository(api)
    val catalog = CatalogRepository(api)
    val orders = OrderRepository(api)
    val peers = PeerDirectory(api)

    val chat = NexconnChat(context, api, session, appScope)
    val notifier = Notifier(context, chat, peers, appScope)

    val location = LocationClient(context)
    val voiceRecorder = VoiceRecorder(context)
    val voicePlayer = VoicePlayer()

    /** Ends the session everywhere: chat connection, caches and stored credentials. */
    fun signOut() {
        chat.stop()
        voicePlayer.stop()
        voiceRecorder.stopAndDiscard()
        shops.clear()
        catalog.clear()
        orders.clear()
        peers.clear()
        session.signOut()
    }
}
