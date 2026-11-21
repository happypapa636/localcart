package app.localcart.ui.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.localcart.BuildConfig
import app.localcart.chat.ChatConnection
import app.localcart.core.AppContainer
import app.localcart.data.repo.Session
import app.localcart.ui.components.KeyValueRow
import app.localcart.ui.components.ScreenHeading
import app.localcart.ui.components.SecondaryButton
import app.localcart.ui.components.SectionLabel
import app.localcart.ui.components.Sheet
import app.localcart.ui.nav.Nav
import app.localcart.ui.theme.Palette

@Composable
fun AccountScreen(container: AppContainer, nav: Nav) {
    val session by container.session.session.collectAsState()
    val connection by container.chat.connection.collectAsState()
    val user = (session as? Session.SignedIn)?.user ?: return
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeading(title = "Account")
        Sheet(Modifier.padding(horizontal = 20.dp)) {
            Column {
                Text(user.name, style = MaterialTheme.typography.titleLarge)
                Text("+91 ${user.phone}", style = MaterialTheme.typography.bodyMedium, color = Palette.InkMuted)
                Spacer(Modifier.height(8.dp))
                KeyValueRow("Account type", if (user.isShopkeeper) "Shopkeeper" else "Customer")
                KeyValueRow(
                    "Chat",
                    when (connection) {
                        ChatConnection.Connected -> "Connected"
                        ChatConnection.Connecting, ChatConnection.Reconnecting -> "Connecting"
                        ChatConnection.Offline -> "Offline"
                        ChatConnection.SignedInElsewhere -> "Open on another phone"
                        ChatConnection.Failed -> "Couldn't connect"
                        ChatConnection.Idle -> "Not connected"
                    },
                    valueColor = if (connection == ChatConnection.Connected) Palette.Leaf else Palette.InkMuted,
                )
            }
        }
        if (!user.isShopkeeper) {
            SectionLabel("Delivery")
            Sheet(Modifier.padding(horizontal = 20.dp)) {
                Column {
                    Text(user.addressText ?: "No address set", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Change delivery location", onClick = nav::location, icon = Icons.Outlined.Place, modifier = Modifier.fillMaxWidth())
                }
            }
        } else {
            SectionLabel("Shop")
            SecondaryButton("Shop settings", onClick = nav::shopSettings, icon = Icons.Outlined.Storefront, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        }
        SectionLabel("Notifications")
        Text(
            "While LocalCart is open or in the background, new messages, carts and bills alert you instantly. " +
                "Alerts after the app is fully closed need push notifications, which this build does not include yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.InkMuted,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(24.dp))
        SecondaryButton(
            "Sign out",
            onClick = { confirmSignOut = true },
            icon = Icons.AutoMirrored.Outlined.Logout,
            color = Palette.Chilli,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        Text("LocalCart ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(20.dp))
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of LocalCart?") },
            text = { Text("You'll stop receiving messages on this phone until you sign in again.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; container.signOut() }) { Text("Sign out", color = Palette.Chilli) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Stay signed in") } },
        )
    }
}
