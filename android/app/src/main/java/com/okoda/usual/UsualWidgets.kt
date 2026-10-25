package com.okoda.usual

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.withTimeoutOrNull

private fun widgetIntent(context: Context, route: AppDestination) = Intent(context, MainActivity::class.java).setData(Uri.parse(route.link())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
class MemoryWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as UsualApplication
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        var route: AppDestination? = null
        var bitmap: Bitmap? = null
        if (uid != null && app.privacy.widgetAllowed(uid)) {
            val snapshot = withTimeoutOrNull(20_000) { try { app.backend.call("widget.snapshot", emptyMap(), expectedUid = uid) } catch (_: UserFacingException) { null } }
            if (snapshot?.get("visible") == true && FirebaseAuth.getInstance().currentUser?.uid == uid && app.privacy.widgetAllowed(uid)) {
                val bytes = (snapshot["base64"] as? String)?.let { Base64.decode(it, Base64.DEFAULT) }
                if (bytes != null && bytes.size <= 512 * 1024) {
                    bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    route = AppDestination("content", snapshot["coupleId"] as? String, snapshot["contentId"] as? String)
                }
            }
        }
        val image = bitmap
        val destination = route
        provideContent {
            Column(GlanceModifier.fillMaxSize().padding(12.dp)) {
                Text(context.getString(R.string.app_name))
                if (image != null && destination != null) Image(ImageProvider(image), "Your latest received moment", GlanceModifier.fillMaxWidth().height(160.dp).clickable(actionStartActivity(widgetIntent(context, destination))))
                else Text("Open Usual to connect and manage widget previews.", GlanceModifier.clickable(actionStartActivity(widgetIntent(context, AppDestination("game")))))
            }
        }
    }
}
class QuickActionWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Column(GlanceModifier.fillMaxSize().padding(12.dp)) {
            Text(context.getString(R.string.app_name))
            WidgetShortcut(context, "Draw", "draw")
            WidgetShortcut(context, "Send a photo", "photo")
            WidgetShortcut(context, "Play together", "game")
        } }
    }
}
@Composable private fun WidgetShortcut(context: Context, title: String, action: String) { Text(title, GlanceModifier.fillMaxWidth().padding(8.dp).clickable(actionStartActivity(widgetIntent(context, AppDestination(action))))) }
class MemoryWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = MemoryWidget() }
class QuickActionWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = QuickActionWidget() }
class PrivateRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as UsualApplication
        try {
            MemoryWidget().updateAll(app)
            QuickActionWidget().updateAll(app)
            withTimeoutOrNull(10_000) { app.privacy.synchronizeToken() }
            return Result.success()
        } catch (_: Exception) { return if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }
}
