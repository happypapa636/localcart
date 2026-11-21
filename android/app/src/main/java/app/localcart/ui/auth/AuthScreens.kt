package app.localcart.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.ShoppingBasket
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.localcart.core.AppContainer
import app.localcart.data.api.ApiException
import app.localcart.domain.Phone
import app.localcart.ui.components.AppTopBar
import app.localcart.ui.components.Field
import app.localcart.ui.components.InlineNotice
import app.localcart.ui.components.PrimaryButton
import app.localcart.ui.components.QuietButton
import app.localcart.ui.theme.LocalFigures
import app.localcart.ui.theme.Palette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(val busy: Boolean = false, val error: String? = null)

class AuthViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state = _state.asStateFlow()

    fun signUp(name: String, phone: String, password: String, role: String) = run {
        container.auth.signUp(name, phone, password, role)
    }

    fun signIn(phone: String, password: String) = run { container.auth.signIn(phone, password) }

    private fun run(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = AuthUiState(busy = true)
        viewModelScope.launch {
            try {
                block()
                _state.value = AuthUiState()
            } catch (e: ApiException) {
                _state.value = AuthUiState(error = e.message)
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}

/** First screen: what LocalCart does, and which side of the counter you are on. */
@Composable
fun WelcomeScreen(onChooseRole: (String) -> Unit, onSignIn: () -> Unit) {
    var role by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().background(Palette.Paper).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        Text("LocalCart", style = LocalFigures.current.medium, color = Palette.Leaf)
        Spacer(Modifier.height(20.dp))
        Text("Order from the shops on your street, by chat.", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(14.dp))
        Text(
            "Type or say your list the way you would on WhatsApp. LocalCart turns it into a cart, the shop sends the bill, and you track it to your door.",
            style = MaterialTheme.typography.bodyLarge,
            color = Palette.InkMuted,
        )
        Spacer(Modifier.height(32.dp))
        Text("How will you use LocalCart?", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        RoleOption(
            title = "I'm ordering",
            body = "Find open shops nearby and order by chat.",
            icon = Icons.Outlined.ShoppingBasket,
            selected = role == "customer",
            onClick = { role = "customer" },
        )
        Spacer(Modifier.height(10.dp))
        RoleOption(
            title = "I run a shop",
            body = "Take orders, send bills and deliver.",
            icon = Icons.Outlined.Storefront,
            selected = role == "shopkeeper",
            onClick = { role = "shopkeeper" },
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Create account", onClick = { role?.let(onChooseRole) }, enabled = role != null, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("Already have an account?", style = MaterialTheme.typography.bodyMedium, color = Palette.InkMuted)
            QuietButton("Sign in", onSignIn)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RoleOption(title: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) Palette.LeafTint else Palette.Sheet,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Palette.Leaf else Palette.Rule),
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(MaterialTheme.shapes.small).background(if (selected) Palette.Leaf else Palette.InkTint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = if (selected) Palette.Paper else Palette.Ink)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = Palette.InkMuted)
            }
        }
    }
}

@Composable
fun SignUpScreen(container: AppContainer, role: String, onBack: () -> Unit, onSignIn: () -> Unit) {
    val vm: AuthViewModel = viewModel { AuthViewModel(container) }
    val state by vm.state.collectAsState()
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }

    val nameError = if (submitted && name.trim().length < 2) "Enter your name." else null
    val phoneError = if (submitted && Phone.normalize(phone) == null) "Enter a valid 10-digit mobile number." else null
    val passwordError = if (submitted && password.length < 8) "Use at least 8 characters." else null

    Column(Modifier.fillMaxSize().background(Palette.Paper).statusBarsPadding().imePadding()) {
        AppTopBar(title = if (role == "shopkeeper") "Create a shop account" else "Create your account", onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(
                if (role == "shopkeeper") "You'll set up your shop and catalog next." else "You'll choose your delivery location next.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.InkMuted,
            )
            Spacer(Modifier.height(20.dp))
            Field(name, { name = it }, label = if (role == "shopkeeper") "Your name" else "Name", error = nameError)
            Spacer(Modifier.height(8.dp))
            Field(phone, { phone = it }, label = "Mobile number", prefix = "+91 ", keyboardType = KeyboardType.Phone, error = phoneError)
            Spacer(Modifier.height(8.dp))
            PasswordField(password, { password = it }, showPassword, { showPassword = !showPassword }, passwordError, supporting = "At least 8 characters")
            state.error?.let {
                Spacer(Modifier.height(12.dp))
                InlineNotice(it)
            }
            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                "Create account",
                onClick = {
                    submitted = true
                    val p = Phone.normalize(phone)
                    if (name.trim().length >= 2 && p != null && password.length >= 8) vm.signUp(name, p, password, role)
                },
                loading = state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("Already registered?", style = MaterialTheme.typography.bodyMedium, color = Palette.InkMuted)
                QuietButton("Sign in", onSignIn)
            }
        }
    }
}

@Composable
fun SignInScreen(container: AppContainer, onBack: () -> Unit) {
    val vm: AuthViewModel = viewModel { AuthViewModel(container) }
    val state by vm.state.collectAsState()
    var phone by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val phoneError = if (submitted && Phone.normalize(phone) == null) "Enter a valid 10-digit mobile number." else null
    val passwordError = if (submitted && password.isEmpty()) "Enter your password." else null

    Column(Modifier.fillMaxSize().background(Palette.Paper).statusBarsPadding().imePadding()) {
        AppTopBar(title = "Sign in", onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Welcome back. Use the mobile number you registered with.", style = MaterialTheme.typography.bodyMedium, color = Palette.InkMuted)
            Spacer(Modifier.height(20.dp))
            Field(phone, { phone = it }, label = "Mobile number", prefix = "+91 ", keyboardType = KeyboardType.Phone, error = phoneError)
            Spacer(Modifier.height(8.dp))
            PasswordField(password, { password = it }, showPassword, { showPassword = !showPassword }, passwordError)
            state.error?.let {
                Spacer(Modifier.height(12.dp))
                InlineNotice(it)
            }
            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                "Sign in",
                onClick = {
                    submitted = true
                    val p = Phone.normalize(phone)
                    if (p != null && password.isNotEmpty()) vm.signIn(p, password)
                },
                loading = state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onChange: (String) -> Unit,
    visible: Boolean,
    onToggle: () -> Unit,
    error: String?,
    supporting: String? = null,
) {
    Field(
        value,
        onChange,
        label = "Password",
        keyboardType = KeyboardType.Password,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        error = error,
        supporting = supporting,
        trailing = {
            IconButton(onClick = onToggle) {
                Icon(
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "Hide password" else "Show password",
                )
            }
        },
    )
}
