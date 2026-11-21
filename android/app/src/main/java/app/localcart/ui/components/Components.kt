package app.localcart.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.localcart.ui.theme.AnekDisplay
import app.localcart.ui.theme.Palette
import coil3.compose.AsyncImage

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    destructive: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.heightIn(min = 50.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (destructive) Palette.Chilli else Palette.Leaf,
            contentColor = Palette.Paper,
            disabledContainerColor = Palette.Ink.copy(alpha = 0.10f),
            disabledContentColor = Palette.InkFaint,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Palette.Paper, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    color: Color = Palette.Ink,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (enabled) color.copy(alpha = 0.28f) else Palette.Rule),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color, disabledContentColor = Palette.InkFaint),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Palette.Leaf, enabled: Boolean = true) {
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = color)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    prefix: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = Palette.InkFaint) } },
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = singleLine,
        minLines = minLines,
        visualTransformation = visualTransformation,
        prefix = prefix?.let { { Text(it) } },
        trailingIcon = trailing,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Leaf,
            unfocusedBorderColor = Palette.RuleStrong,
            focusedLabelColor = Palette.Leaf,
            unfocusedContainerColor = Palette.Sheet,
            focusedContainerColor = Palette.Sheet,
            errorContainerColor = Palette.Sheet,
            cursorColor = Palette.Leaf,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Paper, scrolledContainerColor = Palette.Paper),
    )
}

/** Large left-aligned screen heading used on tab roots. */
@Composable
fun ScreenHeading(title: String, modifier: Modifier = Modifier, subtitle: String? = null, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = Palette.InkMuted,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { heading() },
    )
}

/** Empty states are invitations to act: one sentence of direction and one action. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Palette.LeafTint), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Palette.Leaf, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Palette.InkMuted, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            PrimaryButton(action, onAction)
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val offline = message.contains("internet", ignoreCase = true) || message.contains("connection", ignoreCase = true)
    EmptyState(
        title = if (offline) "You're offline" else "Couldn't load this",
        body = message,
        icon = if (offline) Icons.Outlined.CloudOff else Icons.Outlined.ErrorOutline,
        action = "Try again",
        onAction = onRetry,
        modifier = modifier,
    )
}

@Composable
fun LoadingState(modifier: Modifier = Modifier, label: String = "Loading") {
    Box(modifier.fillMaxWidth().padding(48.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Palette.Leaf, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
    }
}

@Composable
fun FullScreenLoading() {
    Box(Modifier.fillMaxSize().background(Palette.Paper), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Palette.Leaf, strokeWidth = 2.5.dp)
    }
}

/** Inline banner for errors and notices inside forms and screens. */
@Composable
fun InlineNotice(text: String, modifier: Modifier = Modifier, isError: Boolean = true, action: String? = null, onAction: (() -> Unit)? = null) {
    val tint = if (isError) Palette.Chilli else Palette.Leaf
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (isError) Palette.ChilliTint else Palette.LeafTint)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tint, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Spacer(Modifier.width(8.dp))
            Text(action, style = MaterialTheme.typography.labelLarge, color = tint, modifier = Modifier.clickable(onClick = onAction).padding(4.dp))
        }
    }
}

/** Square photo, or the first letter of the name in a tinted tile when there is no photo. */
@Composable
fun Thumb(url: String?, name: String, modifier: Modifier = Modifier, size: Dp = 48.dp, round: Boolean = false) {
    val shape = if (round) CircleShape else RoundedCornerShape(8.dp)
    Box(
        modifier.size(size).clip(shape).background(Palette.InkTint).border(1.dp, Palette.Rule, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                name.trim().firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = AnekDisplay, fontSize = (size.value * 0.42f).sp),
                color = Palette.Ink.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier.heightIn(min = 20.dp).clip(CircleShape).background(Palette.Chilli).padding(horizontal = 6.dp, vertical = 1.dp)
            .semantics { contentDescription = "$count unread" },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 99) "99+" else count.toString(), color = Palette.Paper, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight(600)))
    }
}

enum class Tone { Neutral, Positive, Negative, Strong }

/** Small status label, e.g. "Open now", "Out for delivery", "Cancelled". */
@Composable
fun StatusTag(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val (bg, fg) = when (tone) {
        Tone.Neutral -> Palette.InkTint to Palette.InkMuted
        Tone.Positive -> Palette.LeafTint to Palette.Leaf
        Tone.Negative -> Palette.ChilliTint to Palette.Chilli
        Tone.Strong -> Palette.Ink to Palette.Paper
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** Dashed rule, like the tear line above the total on a printed bill. */
@Composable
fun DashedRule(modifier: Modifier = Modifier, color: Color = Palette.RuleStrong) {
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
        )
    }
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Palette.Rule))
}

/** A raised white sheet with a hairline border: the base for slips, forms and list groups. */
@Composable
fun Sheet(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Palette.Sheet,
        border = BorderStroke(1.dp, Palette.Rule),
    ) {
        Box(Modifier.padding(padding)) { content() }
    }
}

@Composable
fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Palette.Paper else Palette.Ink,
        modifier = modifier
            .clip(shape)
            .background(if (selected) Palette.Ink else Palette.Sheet)
            .border(1.dp, if (selected) Palette.Ink else Palette.RuleStrong, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Palette.Ink) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.InkMuted)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, textAlign = TextAlign.End)
    }
}
