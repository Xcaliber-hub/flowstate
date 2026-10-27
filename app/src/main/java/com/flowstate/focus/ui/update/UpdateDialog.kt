package com.flowstate.focus.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.flowstate.focus.ui.components.CardShape
import com.flowstate.focus.ui.components.DepthSurface
import java.util.Locale

/** Shows the "Update available" card over the app when a newer GitHub release exists. */
@Composable
fun UpdatePrompt(vm: UpdateViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.retryInstall() }
    if (state is UpdateState.Hidden) return

    val downloading = state is UpdateState.Downloading
    Dialog(
        onDismissRequest = { if (!downloading) vm.later() },
        properties = DialogProperties(dismissOnClickOutside = !downloading, dismissOnBackPress = !downloading),
    ) {
        DepthSurface(shape = CardShape, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp)) {
                when (val s = state) {
                    is UpdateState.Available -> AvailableContent(s, vm)
                    is UpdateState.Downloading -> DownloadingContent(s)
                    is UpdateState.NeedsPermission -> PermissionContent(vm)
                    is UpdateState.Failed -> FailedContent(vm)
                    UpdateState.Hidden -> Unit
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(52.dp).background(cs.primary.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = cs.primary) }
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
}

@Composable
private fun AvailableContent(s: UpdateState.Available, vm: UpdateViewModel) {
    val cs = MaterialTheme.colorScheme
    val size = if (s.release.apkSizeBytes > 0) " · ${formatMb(s.release.apkSizeBytes)}" else ""
    Header("Update available", "Version ${s.release.version} is ready (you have ${s.currentVersion})$size")
    if (s.release.notes.isNotBlank()) {
        Spacer(Modifier.height(16.dp))
        Text("What's new", style = MaterialTheme.typography.labelLarge, color = cs.primary)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().heightIn(max = 180.dp)
                .background(cs.surfaceContainerHigh.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                .padding(14.dp)
                .verticalScroll(rememberScrollState())
        ) { Text(s.release.notes.replace("**", ""), style = MaterialTheme.typography.bodyMedium) }
    }
    Spacer(Modifier.height(20.dp))
    Button(onClick = vm::update, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Update now") }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = vm::skip) { Text("Skip this version", color = cs.onSurfaceVariant) }
        TextButton(onClick = vm::later) { Text("Later") }
    }
}

@Composable
private fun DownloadingContent(s: UpdateState.Downloading) {
    Header("Downloading ${s.release.version}", "${(s.progress * 100).toInt()}% · keep Flowstate open")
    Spacer(Modifier.height(20.dp))
    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().height(8.dp), strokeCap = StrokeCap.Round)
}

@Composable
private fun ColumnScope.PermissionContent(vm: UpdateViewModel) {
    Header("One more step", "Allow Flowstate to install updates, then come back here. You only do this once.")
    Spacer(Modifier.height(20.dp))
    Button(onClick = vm::openPermissionSettings, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Open settings") }
    TextButton(onClick = vm::later, modifier = Modifier.align(Alignment.End)) { Text("Later") }
}

@Composable
private fun ColumnScope.FailedContent(vm: UpdateViewModel) {
    Header("Download failed", "Check your connection and try again.")
    Spacer(Modifier.height(20.dp))
    Button(onClick = vm::update, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Try again") }
    TextButton(onClick = vm::later, modifier = Modifier.align(Alignment.End)) { Text("Later") }
}

private fun formatMb(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1_048_576f)
