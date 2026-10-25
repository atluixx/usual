package com.okoda.usual

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date

@Composable
fun PairingPanel(model: CoupleViewModel, compact: Boolean = false) {
    val ui by model.state.collectAsStateWithLifecycle()
    val state = ui.couple
    var entered by remember(ui.uid) { mutableStateOf("") }
    var confirmation by remember(ui.uid) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    if (compact && state.status == "paired") {
        Text("Together with " + state.partnerName, style = MaterialTheme.typography.titleMedium)
        if (state.cached) Text("Offline · last confirmed pairing. Sends will retry when connected.")
        return
    }
    Text("Your space for two", style = MaterialTheme.typography.titleLarge)
    when (state.status) {
        "loading" -> Text("Checking your pairing…")
        "paired" -> {
            Text("Paired with " + state.partnerName)
            TextButton(onClick = { confirmation = "unpair" }, enabled = !ui.busy) { Text("Unpair") }
        }
        "inviting" -> {
            val code = model.code()
            if (code != null) {
                Text(code.chunked(4).joinToString("-"), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = {
                    val clip = ClipData.newPlainText("Private pairing code", code)
                    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                }) { Text("Copy private code") }
            } else Text("The code is no longer on this device. Cancel this invitation to make another.")
            state.expiresAt?.let { Text("Expires " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))) }
            if (state.claimantName != null) {
                Text(state.claimantName + " wants to pair. Confirm it’s your partner.")
                Button(onClick = { confirmation = "pair" }, enabled = !ui.busy) { Text("Confirm pairing") }
            } else Text("Share this code privately with your partner. You’ll confirm their request here.")
            TextButton(onClick = model::cancel, enabled = !ui.busy) { Text("Cancel invitation") }
        }
        "requested" -> {
            Text("Waiting for " + state.partnerName + " to confirm your request.")
            TextButton(onClick = model::cancel, enabled = !ui.busy) { Text("Cancel request") }
        }
        "unpaired" -> {
            Text("No registration or Google account needed. Share a private code and confirm with each other.")
            Button(onClick = model::create, enabled = !ui.busy) { Text("Create pairing code") }
            OutlinedTextField(value = entered, onValueChange = { entered = it.take(40) }, label = { Text("Partner’s pairing code") }, singleLine = true, enabled = !ui.busy)
            Button(onClick = { model.claim(entered) }, enabled = !ui.busy) { Text("Request to pair") }
        }
        else -> Text("Pairing couldn’t refresh.")
    }
    state.error?.let { Text(it) }
    ui.notice?.let { Text(it) }
    TextButton(onClick = model::refresh, enabled = !ui.busy) { Text("Refresh pairing") }
    TextButton(onClick = { confirmation = "delete" }, enabled = !ui.busy) { Text("Delete my identity") }
    confirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(when (action) { "pair" -> "Pair with this person?"; "unpair" -> "Unpair and delete shared data?"; else -> "Delete your identity?" }) },
            text = { Column { Text(when (action) {
                "pair" -> "Confirm with your partner outside the app that this is their request. Only the two of you will share this space."
                "unpair" -> "Both people immediately lose access to this couple. All its shared content and games will be scheduled for deletion."
                else -> "Your profile and identity will be deleted. If paired, both people lose this couple and all its shared content and games will be scheduled for deletion. This cannot be undone."
            }) } },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                when (action) { "pair" -> model.confirm(); "unpair" -> model.unpair(); else -> model.deleteAccount() }
            }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Keep as is") } },
        )
    }
}
