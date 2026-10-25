package com.okoda.usual

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.Manifest
import android.os.Build
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun PrivacySettings(controller: PrivacyController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permission by remember { mutableStateOf(notificationPermission(context)) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it; controller.permissionChanged() }
    Text("Notifications and widgets", style = MaterialTheme.typography.titleMedium)
    if (!state.ready) Text("Connect to load privacy preferences.")
    Row { Checkbox(state.contentNotifications, { controller.set(content = it) }, enabled = state.ready && !state.busy); Text("New moments") }
    Row { Checkbox(state.gameNotifications, { controller.set(games = it) }, enabled = state.ready && !state.busy); Text("Game invitations and turns") }
    if (!permission) {
        Text("Notifications are optional. Pairing, sending and games work without them.")
        Button(onClick = { if (Build.VERSION.SDK_INT >= 33) request.launch(Manifest.permission.POST_NOTIFICATIONS) else controller.permissionChanged() }) { Text("Allow notifications") }
    }
    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Android notification settings") }
    Row { Checkbox(state.widgetPreview, { controller.set(widget = it) }, enabled = state.ready && !state.busy); Text("Show received photos/drawings in memory widget") }
    Text("Widget previews are visible to anyone using your home screen. A launcher can retain a preview while offline; turn previews off before sharing your device.")
    Text("Add Memory or Quick actions from your Android launcher’s widget picker.")
    val widgets = AppWidgetManager.getInstance(context)
    if (widgets.isRequestPinAppWidgetSupported) {
        TextButton(onClick = { widgets.requestPinAppWidget(ComponentName(context, MemoryWidgetReceiver::class.java), null, null) }) { Text("Add memory widget") }
        TextButton(onClick = { widgets.requestPinAppWidget(ComponentName(context, QuickActionWidgetReceiver::class.java), null, null) }) { Text("Add quick-action widget") }
    }
    TextButton(onClick = controller::refreshWidgets) { Text("Refresh widgets") }
    state.notice?.let { Text(it) }
}
