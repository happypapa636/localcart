package app.localcart.data.repo

import android.content.Context
import app.localcart.core.SecureStore
import app.localcart.data.api.AppJson
import app.localcart.data.api.UserDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface Session {
    data object SignedOut : Session
    data class SignedIn(val user: UserDto) : Session
}

/**
 * The signed-in user and their API token, kept in [SecureStore] so a session survives process death
 * and restarts without ever being written in plain text.
 */
class SessionRepository(context: Context) {
    private val store = SecureStore(context)
    private val plain = context.getSharedPreferences("localcart_prefs", Context.MODE_PRIVATE)

    @Volatile private var token: String? = store.get(KEY_TOKEN)

    private val _session = MutableStateFlow(restore())
    val session: StateFlow<Session> = _session.asStateFlow()

    val currentUser: UserDto? get() = (session.value as? Session.SignedIn)?.user

    fun token(): String? = token

    private fun restore(): Session {
        val json = store.get(KEY_USER) ?: return Session.SignedOut
        if (token == null) return Session.SignedOut
        val user = runCatching { AppJson.decodeFromString(UserDto.serializer(), json) }.getOrNull()
        return if (user != null) Session.SignedIn(user) else Session.SignedOut
    }

    fun signIn(token: String, user: UserDto) {
        this.token = token
        store.put(KEY_TOKEN, token)
        updateUser(user)
    }

    fun updateUser(user: UserDto) {
        store.put(KEY_USER, AppJson.encodeToString(UserDto.serializer(), user))
        _session.value = Session.SignedIn(user)
    }

    /** Chat credentials are cached so the app can connect to Nexconn offline-first on the next launch. */
    fun cacheChatToken(token: String?) = store.put(KEY_CHAT_TOKEN, token)
    fun cachedChatToken(): String? = store.get(KEY_CHAT_TOKEN)

    var appKey: String?
        get() = plain.getString(KEY_APP_KEY, null)
        set(value) = plain.edit().putString(KEY_APP_KEY, value).apply()

    var notificationsAsked: Boolean
        get() = plain.getBoolean(KEY_NOTIF_ASKED, false)
        set(value) = plain.edit().putBoolean(KEY_NOTIF_ASKED, value).apply()

    fun signOut() {
        token = null
        store.put(KEY_TOKEN, null)
        store.put(KEY_USER, null)
        store.put(KEY_CHAT_TOKEN, null)
        _session.value = Session.SignedOut
    }

    private companion object {
        const val KEY_TOKEN = "api_token"
        const val KEY_USER = "user"
        const val KEY_CHAT_TOKEN = "chat_token"
        const val KEY_APP_KEY = "nexconn_app_key"
        const val KEY_NOTIF_ASKED = "notifications_asked"
    }
}
