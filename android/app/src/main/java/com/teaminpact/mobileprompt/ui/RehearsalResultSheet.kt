package com.teaminpact.mobileprompt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teaminpact.mobileprompt.R
import com.teaminpact.mobileprompt.engine.RehearsalReport
import com.teaminpact.mobileprompt.engine.StuckReason
import kotlinx.coroutines.launch
import java.util.Locale

/** Summary after a rehearsal (or a take): time, pace, pauses, stuck sentences. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RehearsalResultSheet(report: RehearsalReport, onAgain: () -> Unit, onRecord: () -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun closeThen(action: () -> Unit) {
        scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss(); action() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(if (report.kind == RehearsalReport.Kind.REHEARSAL) R.string.rehearsal_results else R.string.take_results),
                style = MaterialTheme.typography.titleLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile(Modifier.weight(1f), stringResource(R.string.total_time), timeString(report.seconds))
                Tile(Modifier.weight(1f), stringResource(R.string.pace), paceString(report))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile(
                    Modifier.weight(1f), stringResource(R.string.pauses),
                    stringResource(R.string.pauses_value, report.pauseCount.toString(), secondsString(report.pauseSeconds)),
                )
                Tile(Modifier.weight(1f), stringResource(R.string.stuck_spots), report.stuck.size.toString())
            }
            Text(stringResource(R.string.stuck_sentences), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (report.stuck.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.stuck_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                for (s in report.stuck) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(s.text, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (reason in s.reasons) {
                                Text(
                                    reasonText(reason),
                                    Modifier
                                        .background(Color(0xFFFFA726).copy(alpha = 0.18f), CircleShape)
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                    color = Color(0xFFFFA726),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            if (report.kind == RehearsalReport.Kind.REHEARSAL) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { closeThen(onAgain) }, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.rehearse_again))
                    }
                    Button(onClick = { closeThen(onRecord) }, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Filled.FiberManualRecord, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.go_record))
                    }
                }
            } else {
                Button(onClick = { closeThen {} }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.ok))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Tile(modifier: Modifier, title: String, value: String) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun paceString(report: RehearsalReport): String {
    val n = Math.round(report.pace).toString()
    return if (report.paceUnit == RehearsalReport.PaceUnit.WORDS_PER_MINUTE) stringResource(R.string.pace_wpm, n)
    else stringResource(R.string.pace_syl, n)
}

@Composable
private fun reasonText(reason: StuckReason): String = when (reason) {
    is StuckReason.Pause -> stringResource(R.string.reason_pause, secondsString(reason.seconds))
    StuckReason.Reread -> stringResource(R.string.reason_reread)
    StuckReason.Lost -> stringResource(R.string.reason_lost)
    StuckReason.Slow -> stringResource(R.string.reason_slow)
}

internal fun timeString(seconds: Double): String {
    val s = Math.round(seconds).toInt()
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

internal fun secondsString(seconds: Double): String =
    if (seconds < 10) String.format(Locale.US, "%.1f", seconds) else Math.round(seconds).toString()
