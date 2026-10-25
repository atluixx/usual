package com.okoda.usual

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.installations.FirebaseInstallations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

data class PrivacyState(val uid: String? = null, val ready: Boolean = false, val contentNotifications: Boolean = false, val gameNotifications: Boolean = false, val widgetPreview: Boolean = false, val busy: Boolean = false, val notice: String? = null)
class PrivacyController(private val app: UsualApplication, private val auth: FirebaseAuth, private val database: FirebaseFirestore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val local = app.getSharedPreferences("privacy", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(PrivacyState())
    val state = mutable.asStateFlow()
    private var listener: ListenerRegistration? = null
    private val tokenLock = Mutex()
    init {
        FirebaseMessaging.getInstance().isAutoInitEnabled = false
        WorkManager.getInstance(app).enqueueUniquePeriodicWork("private-refresh", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<PrivateRefreshWorker>(15, TimeUnit.MINUTES).build())
        auth.addAuthStateListener { current ->
            val uid = current.currentUser?.uid
            if (uid != mutable.value.uid) {
                listener?.remove(); mutable.value = PrivacyState(uid = uid)
                hidePreviews()
                if (uid != null) listener = database.collection("preferences").document(uid).addSnapshotListener(MetadataChanges.INCLUDE) { document, error ->
                    if (auth.currentUser?.uid != uid) return@addSnapshotListener
                    if (error != null) { hidePreviews(); mutable.update { it.copy(ready = false, notice = "Privacy preferences unavailable. Reconnect to refresh.") } }
                    else if (document != null && !document.metadata.isFromCache) {
                        mutable.update { it.copy(ready = true, contentNotifications = document.getBoolean("contentNotifications") == true, gameNotifications = document.getBoolean("gameNotifications") == true, widgetPreview = document.getBoolean("widgetPreview") == true) }
                        local.edit().putString("widget_uid", uid.takeIf { mutable.value.widgetPreview }).apply()
                        if (!mutable.value.contentNotifications && !mutable.value.gameNotifications) clearNotifications()
                        refreshWidgets(); scope.launch { synchronizeToken() }
                    }
                }
            }
        }
    }
    fun widgetAllowed(uid: String) = local.getString("widget_uid", null) == uid
    fun clearNotifications() { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll() }
    fun hidePreviews() { local.edit().remove("widget_uid").apply(); clearNotifications(); refreshWidgets() }
    fun refreshWidgets() {
        WorkManager.getInstance(app).enqueueUniqueWork("widget-refresh", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<PrivateRefreshWorker>().build())
    }
    fun set(content: Boolean = mutable.value.contentNotifications, games: Boolean = mutable.value.gameNotifications, widget: Boolean = mutable.value.widgetPreview) {
        val uid = mutable.value.uid ?: return
        if (mutable.value.busy || !mutable.value.ready) return
        if (!widget) hidePreviews()
        mutable.update { it.copy(busy = true, notice = null) }
        scope.launch {
            try {
                app.backend.call("preferences.set", mapOf("contentNotifications" to content, "gameNotifications" to games, "widgetPreview" to widget), expectedUid = uid)
                if (auth.currentUser?.uid == uid) mutable.update { it.copy(contentNotifications = content, gameNotifications = games, widgetPreview = widget) }
                synchronizeToken()
            } catch (error: UserFacingException) { if (auth.currentUser?.uid == uid) mutable.update { it.copy(notice = error.message) } }
            finally { if (auth.currentUser?.uid == uid) mutable.update { it.copy(busy = false) } }
        }
    }
    fun permissionChanged() { scope.launch { synchronizeToken() } }
    fun receivedToken(token: String) { scope.launch { register(token) } }
    suspend fun synchronizeToken() = tokenLock.withLock {
        val uid = auth.currentUser?.uid ?: return@withLock
        if (BuildConfig.USE_FIREBASE_EMULATORS) return@withLock
        val enabled = mutable.value.ready && mutable.value.uid == uid && (mutable.value.contentNotifications || mutable.value.gameNotifications) && notificationPermission(app)
        try {
            if (local.getString("token_uid", null) != uid) {
                if (local.getString("token_uid", null) != null) {
                    FirebaseMessaging.getInstance().unregister().await()
                    FirebaseInstallations.getInstance().delete().await()
                }
                local.edit().remove("token").putString("token_uid", uid).apply()
            }
            if (enabled) {
                // Re-register on foreground/permission changes; server expires idle devices after 30 days.
                FirebaseMessaging.getInstance().register().await()
                register(FirebaseInstallations.getInstance().id.await())
            } else {
                local.getString("token", null)?.takeIf { local.getString("token_uid", null) == uid }?.let {
                    app.backend.call("device.unregister", mapOf("fid" to it), expectedUid = uid)
                }
            }
        } catch (_: Exception) { /* Next foreground or periodic work retries registration. */ }
    }
    private suspend fun register(token: String) {
        val uid = auth.currentUser?.uid ?: return
        if (!mutable.value.ready || mutable.value.uid != uid || !(mutable.value.contentNotifications || mutable.value.gameNotifications) || !notificationPermission(app)) return
        try {
            app.backend.call("device.register", mapOf("fid" to token), expectedUid = uid)
            if (auth.currentUser?.uid == uid) local.edit().putString("token_uid", uid).putString("token", token).apply()
        } catch (_: UserFacingException) { /* Retry on foreground; never log the token. */ }
    }
    suspend fun beforeSignOut() {
        val uid = auth.currentUser?.uid
        hidePreviews()
        mutable.update { it.copy(ready = false) }
        withTimeoutOrNull(3000) {
            try {
                local.getString("token", null)?.takeIf { local.getString("token_uid", null) == uid }?.let {
                    app.backend.call("device.unregister", mapOf("fid" to it), expectedUid = uid)
                }
                FirebaseMessaging.getInstance().unregister().await(); FirebaseInstallations.getInstance().delete().await()
            } catch (_: Exception) { }
        }
        local.edit().remove("token").remove("token_uid").apply()
    }
}
fun notificationPermission(context: Context): Boolean = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
