package com.okoda.usual

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.currentState
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.background
import androidx.compose.ui.graphics.Color
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

private fun widgetIntent(context: Context, route: AppDestination) = Intent(context, MainActivity::class.java).setData(Uri.parse(route.link())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
private val widgetRevision = longPreferencesKey("refresh_revision")
private data class WidgetSnapshot(val revision: Long, val uid: String, val route: AppDestination, val bitmap: Bitmap)
class MemoryWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as UsualApplication
        provideContent {
            val revision = currentState<Preferences>()[widgetRevision] ?: 0L
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            val snapshot by produceState<WidgetSnapshot?>(null, revision, uid) {
                value = null
                if (uid != null && app.privacy.widgetAllowed(uid)) {
                    val result = withTimeoutOrNull(20_000) {
                        try { app.backend.call("widget.snapshot", emptyMap(), expectedUid = uid) }
                        catch (error: CancellationException) { throw error }
                        catch (_: Exception) { null }
                    }
                    if (result?.get("visible") == true && FirebaseAuth.getInstance().currentUser?.uid == uid && app.privacy.widgetAllowed(uid)) {
                        val bytes = (result["base64"] as? String)?.let { Base64.decode(it, Base64.DEFAULT) }
                        if (bytes != null && bytes.size <= 512 * 1024) {
                            val checksum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                            val bitmap = if (checksum == result["sha256"]) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
                            val route = AppDestination("content", result["coupleId"] as? String, result["contentId"] as? String)
                            if (bitmap != null && bitmap.width <= 256 && bitmap.height <= 256 && AppDestination.parse(route.link()) == route) value = WidgetSnapshot(revision, uid, route, bitmap)
                        }
                    }
                }
            }
            // Invalidate the prior image immediately, even while the next fetch is loading.
            val current = snapshot?.takeIf { it.revision == revision && it.uid == uid && app.privacy.widgetAllowed(it.uid) }
            Column(GlanceModifier.fillMaxSize().background(Color.White).padding(12.dp)) {
                Text(context.getString(R.string.app_name))
                if (current != null) Image(ImageProvider(current.bitmap), "Your latest received moment", GlanceModifier.fillMaxWidth().height(160.dp).clickable(actionStartActivity(widgetIntent(context, current.route))))
                else Text("Open " + context.getString(R.string.app_name) + " to connect and manage widget previews.", GlanceModifier.clickable(actionStartActivity(widgetIntent(context, AppDestination("game")))))
            }
        }
    }
}
class QuickActionWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Column(GlanceModifier.fillMaxSize().background(Color.White).padding(12.dp)) {
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
            val memory = MemoryWidget()
            for (id in GlanceAppWidgetManager(app).getGlanceIds(MemoryWidget::class.java)) {
                updateAppWidgetState(app, id) { state -> state[widgetRevision] = (state[widgetRevision] ?: 0L) + 1L }
                memory.update(app, id)
            }
            QuickActionWidget().updateAll(app)
            withTimeoutOrNull(10_000) { app.privacy.synchronizeToken() }
            return Result.success()
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { return if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }
}
