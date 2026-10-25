package com.teaminpact.mobileprompt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.teaminpact.mobileprompt.R
import com.teaminpact.mobileprompt.model.AppSettings
import kotlin.math.roundToInt

/**
 * Live-adjustable prompter settings (also reachable mid-session).
 * `compact`: no dimming scrim, so changes can be previewed on the prompter behind.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(settings: AppSettings, compact: Boolean, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = !compact)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        scrimColor = if (compact) Color.Transparent else androidx.compose.material3.BottomSheetDefaults.ScrimColor,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }

            SectionTitle(stringResource(R.string.section_text))
            SliderRow(
                label = stringResource(R.string.size),
                value = settings.fontSize.toFloat(), range = 18f..56f, steps = 37,
                valueText = "${settings.fontSize.roundToInt()}",
                onChange = { settings.fontSize = it.roundToInt().toDouble() },
            )
            Text(stringResource(R.string.highlight_color), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorChip("yellow", stringResource(R.string.yellow), settings)
                ColorChip("green", stringResource(R.string.green), settings)
                ColorChip("cyan", stringResource(R.string.sky), settings)
                ColorChip("pink", stringResource(R.string.pink), settings)
                ColorChip("none", stringResource(R.string.none), settings)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mirror_mode), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = settings.mirrored, onCheckedChange = { settings.mirrored = it })
            }

            SectionTitle(stringResource(R.string.section_display))
            SliderRow(stringResource(R.string.background_dim), settings.scrimOpacity.toFloat(), 0f..0.7f, 0, null) { settings.scrimOpacity = it.toDouble() }
            SliderRow(stringResource(R.string.reading_line), settings.readingLineFrac.toFloat(), 0.15f..0.6f, 0, null) { settings.readingLineFrac = it.toDouble() }

            SectionTitle(stringResource(R.string.section_box))
            SliderRow(stringResource(R.string.width), settings.portraitWidthFrac.toFloat(), 0.4f..1f, 0, "${(settings.portraitWidthFrac * 100).roundToInt()}%") { settings.portraitWidthFrac = it.toDouble() }
            SliderRow(stringResource(R.string.top_margin), settings.portraitTopFrac.toFloat(), 0f..0.35f, 0, "${(settings.portraitTopFrac * 100).roundToInt()}%") { settings.portraitTopFrac = it.toDouble() }
            SliderRow(stringResource(R.string.bottom_margin), settings.portraitBottomFrac.toFloat(), 0f..0.35f, 0, "${(settings.portraitBottomFrac * 100).roundToInt()}%") { settings.portraitBottomFrac = it.toDouble() }
            Footer(stringResource(R.string.box_footer))

            SectionTitle(stringResource(R.string.section_language))
            Text(stringResource(R.string.app_language), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LangChip("system", stringResource(R.string.system_default), settings)
                LangChip("ko", "한국어", settings)
                LangChip("en", "English", settings)
            }
            Footer(stringResource(R.string.language_footer))

            SectionTitle(stringResource(R.string.section_auto))
            SliderRow(stringResource(R.string.speed), settings.autoSpeed.toFloat(), 10f..150f, 0, "${settings.autoSpeed.roundToInt()}") { settings.autoSpeed = it.toDouble() }

            Footer(stringResource(R.string.about_footer))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 22.dp, bottom = 6.dp),
    )
}

@Composable
private fun Footer(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueText: String?,
    onChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps, modifier = Modifier.weight(1f))
        if (valueText != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                valueText, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                textAlign = TextAlign.End, modifier = Modifier.width(44.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorChip(style: String, label: String, settings: AppSettings) {
    FilterChip(
        selected = settings.highlightStyle == style,
        onClick = { settings.highlightStyle = style },
        label = { Text(label) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LangChip(code: String, label: String, settings: AppSettings) {
    FilterChip(
        selected = settings.appLanguage == code,
        onClick = { settings.chooseAppLanguage(code) },
        label = { Text(label) },
    )
}

@Suppress("unused")
private val spacer = Modifier.height(0.dp)
