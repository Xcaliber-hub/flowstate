package com.flowstate.focus.ui.crash

import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.flowstate.focus.util.CrashReporter

/** 1.9.1: after a crash, offers once to share the report (to WhatsApp, email, anything). */
@Composable
fun CrashPrompt() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashReporter.pending(context)) }
    val text = report ?: return
    AlertDialog(
        onDismissRequest = { report = null },
        title = { Text("Flowstate closed unexpectedly") },
        text = { Text("Send the crash report to the developer so it can be fixed. It has the error, your phone model and Android version, and no messages or personal data.") },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Flowstate crash report")
                    .putExtra(Intent.EXTRA_TEXT, text)
                runCatching { context.startActivity(Intent.createChooser(send, "Send crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                report = null
            }) { Text("Send report") }
        },
        dismissButton = { TextButton(onClick = { report = null }) { Text("Not now") } },
    )
}
