package app.localcart.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** Result of asking for a runtime permission, including the "don't ask again" case. */
enum class PermissionResult { Granted, Denied, Blocked }

class PermissionRequester(
    private val context: Context,
    private val permissions: Array<String>,
    private val launch: (Array<String>) -> Unit,
) {
    fun isGranted(): Boolean = permissions.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Calls back straight away if already granted, otherwise shows the system prompt. */
    fun request() {
        if (isGranted()) {
            pending?.invoke(PermissionResult.Granted)
        } else {
            launch(permissions)
        }
    }

    internal var pending: ((PermissionResult) -> Unit)? = null
}

/**
 * Remembers a launcher for one or more equivalent permissions (granted if any is granted, e.g. coarse
 * or fine location). [onResult] distinguishes a plain denial from "blocked" so the UI can offer Settings.
 */
@Composable
fun rememberPermission(vararg permissions: String, onResult: (PermissionResult) -> Unit): PermissionRequester {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val granted = results.values.any { it }
        val activity = context.findActivity()
        val result = when {
            granted -> PermissionResult.Granted
            activity != null && permissions.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) } -> PermissionResult.Blocked
            else -> PermissionResult.Denied
        }
        onResult(result)
    }
    val requester = remember(permissions.joinToString()) {
        PermissionRequester(context, arrayOf(*permissions)) { launcher.launch(it) }
    }
    requester.pending = onResult
    return requester
}

fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
