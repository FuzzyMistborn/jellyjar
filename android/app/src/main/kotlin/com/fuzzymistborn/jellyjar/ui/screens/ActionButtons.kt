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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fuzzymistborn.jellyjar.ui.theme.IconSize
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
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
        contentPadding = PaddingValues(horizontal = Spacing.xl, vertical = 14.dp),
        modifier = modifier,
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(Spacing.sm))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        colors = if (contentColor != null) {
            ButtonDefaults.outlinedButtonColors(contentColor = contentColor)
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
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
        colors = if (contentColor != null) {
            ButtonDefaults.outlinedButtonColors(contentColor = contentColor)
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
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
