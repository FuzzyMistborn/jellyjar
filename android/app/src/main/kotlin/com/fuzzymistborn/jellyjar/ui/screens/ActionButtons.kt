package com.fuzzymistborn.jellyjar.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fuzzymistborn.jellyjar.ui.theme.IconSize
import com.fuzzymistborn.jellyjar.ui.theme.OnSurface
import com.fuzzymistborn.jellyjar.ui.theme.Primary
import com.fuzzymistborn.jellyjar.ui.theme.Spacing

// Full-size filled action button (Resume/Play/Download) — the app's one "primary" call to action per row.
@Composable
fun PrimaryActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = Primary,
    compact: Boolean = false,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = accentColor,
            contentColor = readableContentColor(accentColor),
        ),
        contentPadding = PaddingValues(horizontal = if (compact) Spacing.md else Spacing.xl, vertical = 14.dp),
        modifier = modifier,
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text,
            style = if (compact) MaterialTheme.typography.labelMedium else LocalTextStyle.current,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Full-size outlined action button. Pass contentColor to recolor for a destructive/error action
// (e.g. Remove); omit text for an icon-only button. Pass compact = true in tighter layouts (the
// tablet two-pane Detail sidebar) to shrink padding/text so labels like "Download" fit without
// clipping in a narrower column.
@Composable
fun SecondaryActionButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    contentColor: Color? = null,
    compact: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        colors = scrimOutlinedButtonColors(contentColor),
        border = scrimOutlinedButtonBorder(contentColor),
        contentPadding = PaddingValues(horizontal = if (compact) Spacing.md else Spacing.xl, vertical = 14.dp),
        modifier = modifier,
    ) {
        Icon(icon, contentDescription = null)
        if (text != null) {
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text,
                style = if (compact) MaterialTheme.typography.labelMedium else LocalTextStyle.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// Small outlined action button for inline rows (episode Resume/Play/Download/Retry/Delete).
@Composable
fun CompactActionButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    contentColor: Color? = null,
) {
    OutlinedButton(
        onClick = onClick,
        colors = scrimOutlinedButtonColors(contentColor),
        border = scrimOutlinedButtonBorder(contentColor),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        modifier = modifier,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(IconSize.sm))
        if (text != null) {
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// Black or white, whichever has the higher WCAG contrast against [background] — a dynamic accent
// can land anywhere from a deep blue to a pale pastel, and a fixed content colour is unreadable
// on one end of that range.
internal fun readableContentColor(background: Color): Color {
    val l = background.luminance()
    val contrastWithBlack = (l + 0.05f) / 0.05f
    val contrastWithWhite = 1.05f / (l + 0.05f)
    return if (contrastWithBlack >= contrastWithWhite) Color.Black else Color.White
}

// Outlined buttons sit directly over backdrop art on Detail/Season, where a transparent fill and
// the theme's grey outline washed out on bright or busy images. A dark translucent fill and a
// bright label/border keep them legible on any background (and look unchanged on plain dark).
@Composable
private fun scrimOutlinedButtonColors(contentColor: Color?) = ButtonDefaults.outlinedButtonColors(
    containerColor = Color.Black.copy(alpha = 0.5f),
    contentColor = contentColor ?: OnSurface,
)

private fun scrimOutlinedButtonBorder(contentColor: Color?) =
    BorderStroke(1.dp, (contentColor ?: OnSurface).copy(alpha = 0.6f))
