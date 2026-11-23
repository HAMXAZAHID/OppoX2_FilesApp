package dev.hamza.explorer.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipColors
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import dev.hamza.explorer.R
import dev.hamza.explorer.core.FileKind
import java.text.DateFormat
import java.util.Date
import java.util.Locale

val Muted = Color(0xFFBDC1C6)
val ErrorColor = Color(0xFFF28B82)
private val RowColor = Color(0xFF202124)
private val FolderColor = Color(0xFF3C2A12)

/** Standard watch screen: a top-aligned scrolling list that works with the crown. No clock: ColorOS shows its own. */
@Composable
fun ListScreen(content: ScalingLazyListScope.() -> Unit) {
    // Start at the top of the list (ColorOS users expect a normal top-aligned list, not Wear OS centring).
    val state = rememberScalingLazyListState(initialCenterItemIndex = 0)
    Scaffold(
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = state) },
    ) {
        ScalingLazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .crownScroll(state),
            autoCentering = null,
            // The default crown handling is far too fast on the OPPO Watch X2; crownScroll replaces it.
            rotaryScrollableBehavior = null,
            anchorType = ScalingLazyListAnchorType.ItemStart,
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 30.dp, bottom = 48.dp),
            content = content,
        )
    }
}

fun ScalingLazyListScope.header(text: String) {
    item {
        ListHeader {
            Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

fun ScalingLazyListScope.note(text: String, color: Color = Muted) {
    item {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.caption2,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun ActionChip(
    label: String,
    @DrawableRes icon: Int,
    secondary: String? = null,
    colors: ChipColors = ChipDefaults.secondaryChipColors(),
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Chip(
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = secondary?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        icon = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(ChipDefaults.IconSize)) },
    )
}

/** A file or folder row: tap opens it, long-press shows its options. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ItemRow(
    name: String,
    kind: FileKind,
    secondary: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(if (kind == FileKind.FOLDER) FolderColor else RowColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Icon(
            painterResource(iconFor(kind)),
            contentDescription = null,
            tint = if (kind == FileKind.FOLDER) Color(0xFFFDBA74) else Color.White,
            modifier = Modifier.size(24.dp),
        )
        Box(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.button)
            if (secondary.isNotEmpty()) {
                Text(secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Muted, style = MaterialTheme.typography.caption2)
            }
        }
    }
}

@DrawableRes
fun iconFor(kind: FileKind): Int = when (kind) {
    FileKind.FOLDER -> R.drawable.ic_folder
    FileKind.IMAGE -> R.drawable.ic_image
    FileKind.TEXT -> R.drawable.ic_text
    FileKind.AUDIO -> R.drawable.ic_audio
    FileKind.VIDEO -> R.drawable.ic_video
    FileKind.APK -> R.drawable.ic_apk
    FileKind.OTHER -> R.drawable.ic_file
}

/** Text box that opens the watch keyboard when tapped. The keyboard's Done key calls [onDone]. */
@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, onDone: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.caption2, color = Muted, modifier = Modifier.padding(start = 10.dp, bottom = 2.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF303134), RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        )
    }
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.getDefault(), if (v < 10) "%.1f %s" else "%.0f %s", v, units[i])
}

fun formatDate(millis: Long): String =
    if (millis <= 0) "" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

fun formatTime(ms: Int): String {
    val s = (ms.coerceAtLeast(0)) / 1000
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}
