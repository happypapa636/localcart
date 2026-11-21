package app.localcart

import android.content.Intent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.flow.MutableStateFlow
import app.localcart.ui.nav.LocalCartRoot
import app.localcart.ui.theme.LocalCartTheme

class MainActivity : ComponentActivity() {
    /** Chat to open when launched from a message notification. */
    private val pendingChat = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // LocalCart is a light-only design: always use dark status and navigation bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        pendingChat.value = intent?.getStringExtra(EXTRA_OPEN_CHAT)
        val container = (application as LocalCartApp).container
        setContent {
            LocalCartTheme {
                LocalCartRoot(container = container, pendingChat = pendingChat)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN_CHAT)?.let { pendingChat.value = it }
    }

    companion object {
        const val EXTRA_OPEN_CHAT = "open_chat"
    }
}
