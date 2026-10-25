package com.okoda.usual

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.concurrent.TimeUnit

fun scheduleSend(context: Context, value: PendingSend) {
    val request = OneTimeWorkRequestBuilder<SendWorker>()
        .setInputData(workDataOf("id" to value.id))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
        .addTag("owner:" + value.uid).addTag("couple:" + value.coupleId)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork("send:" + value.id, ExistingWorkPolicy.KEEP, request)
}

class SendWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as UsualApplication
        val id = inputData.getString("id") ?: return Result.failure()
        var value = app.outbox.get(id) ?: return Result.success()
        fun owner(): Boolean = FirebaseAuth.getInstance().currentUser?.uid == value.uid
        if (!owner()) return Result.failure()
        try {
            val statusArgs = mutableMapOf<String, Any?>("requestId" to value.id)
            value.coupleId?.let { statusArgs["coupleId"] = it }
            val previous = app.backend.call("operation.status", statusArgs, expectedUid = value.uid)
            if (previous["completed"] == true) { app.outbox.remove(id); return Result.success() }
            if (value.path != null) {
                val file = File(value.path)
                if (!file.isFile) throw UserFacingException("This local draft is no longer available. Discard it and create another.")
                val permit = mutableMapOf<String, Any?>("uploadId" to id, "sha256" to value.sha256, "bytes" to file.length(), "kind" to if (value.kind == "AVATAR") "AVATAR" else value.kind)
                value.coupleId?.let { permit["coupleId"] = it }
                app.backend.call("media.authorize", permit, expectedUid = value.uid)
                if (!owner()) return Result.failure()
                value = value.copy(state = "Uploading", error = null); app.outbox.update(value)
                val metadata = StorageMetadata.Builder().setContentType(if (value.kind == "DRAWING") "image/png" else "image/jpeg").setCustomMetadata("sha256", value.sha256!!).build()
                FirebaseStorage.getInstance().reference.child("pending/" + value.uid + "/" + id).putFile(Uri.fromFile(file), metadata).await()
            }
            if (!owner()) return Result.failure()
            value = value.copy(state = "Sending", error = null); app.outbox.update(value)
            val args = mutableMapOf<String, Any?>()
            value.coupleId?.let { args["coupleId"] = it }
            if (value.path != null) { args["uploadId"] = id; args["sha256"] = value.sha256 }
            val op = when {
                value.kind == "AVATAR" -> "profile.avatar"
                value.gameId != null -> { args["matchId"] = value.gameId; "game.draw" }
                else -> { args["kind"] = value.kind; args["caption"] = value.caption; "content.send" }
            }
            app.backend.call(op, args, requestId = id, expectedUid = value.uid)
            app.outbox.remove(id)
            return Result.success()
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (!owner()) return Result.failure()
            val retry = (error as? UserFacingException)?.retryable == true ||
                error is StorageException && error.errorCode in listOf(StorageException.ERROR_RETRY_LIMIT_EXCEEDED, StorageException.ERROR_UNKNOWN)
            val message = (error as? UserFacingException)?.message ?: "Upload could not finish. Check your connection and retry."
            app.outbox.update(value.copy(state = if (retry && runAttemptCount < 5) "Retrying" else "Failed", error = message))
            return if (retry && runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}
