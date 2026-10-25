package com.okoda.usual

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class UsualMessagingService : FirebaseMessagingService() {
    override fun onRegistered(token: String) { (application as UsualApplication).privacy.receivedToken(token) }
    override fun onMessageReceived(message: RemoteMessage) {
        val uid = message.data["recipientUid"] ?: return
        val eventId = message.data["eventId"] ?: return
        if (FirebaseAuth.getInstance().currentUser?.uid != uid || !eventId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return
        val work = OneTimeWorkRequestBuilder<NotificationWorker>().setInputData(workDataOf("uid" to uid, "eventId" to eventId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS).addTag("owner:" + uid).build()
        WorkManager.getInstance(this).enqueueUniqueWork("notification:$uid:$eventId", ExistingWorkPolicy.KEEP, work)
    }
    override fun onDeletedMessages() { (application as UsualApplication).privacy.refreshWidgets() }
}
class NotificationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as UsualApplication
        val uid = inputData.getString("uid") ?: return Result.failure()
        val eventId = inputData.getString("eventId") ?: return Result.failure()
        if (FirebaseAuth.getInstance().currentUser?.uid != uid) return Result.success()
        val seen = app.getSharedPreferences("notification_seen", Context.MODE_PRIVATE)
        if (seen.contains(uid + ":" + eventId)) return Result.success()
        try {
            val result = app.backend.call("notification.resolve", mapOf("eventId" to eventId), expectedUid = uid)
            app.privacy.refreshWidgets()
            if (FirebaseAuth.getInstance().currentUser?.uid != uid || !notificationPermission(app)) return Result.success()
            val type = result["type"] as? String ?: return Result.failure()
            val destination = AppDestination(type, result["coupleId"] as? String, result["resourceId"] as? String)
            if (AppDestination.parse(destination.link()) != destination) return Result.failure()
            val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel("private-updates", app.getString(R.string.app_name) + " updates", NotificationManager.IMPORTANCE_DEFAULT).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
            val intent = Intent(app, MainActivity::class.java).setData(Uri.parse(destination.link())).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(app, eventId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(eventId, 0, Notification.Builder(app, "private-updates").setSmallIcon(R.drawable.ic_notification).setContentTitle(app.getString(R.string.app_name))
                .setContentText(if (type == "content") "A new moment is waiting for you." else "There is an update to your game.")
                .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(pending).setAutoCancel(true).build())
            val edit = seen.edit()
            if (seen.all.size >= 200) edit.clear()
            edit.putBoolean(uid + ":" + eventId, true).apply()
            return Result.success()
        } catch (error: CancellationException) { throw error }
        catch (error: UserFacingException) { return if (error.retryable && runAttemptCount < 5) Result.retry() else Result.success() }
    }
}
