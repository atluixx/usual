package com.okoda.usual

import android.app.NotificationManager
import android.content.Context
import androidx.work.WorkManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges

class PrivateSession(private val app: UsualApplication, auth: FirebaseAuth, database: FirebaseFirestore) {
    private val settings = app.getSharedPreferences("private_session", Context.MODE_PRIVATE)
    private var previousUid: String? = settings.getString("uid", null)
    private var membership: ListenerRegistration? = null
    private val listener = FirebaseAuth.AuthStateListener { state ->
        val uid = state.currentUser?.uid
        if (uid != previousUid) {
            previousUid?.let { old ->
                WorkManager.getInstance(app).cancelAllWorkByTag("owner:" + old)
                app.outbox.clear(old)
            }
            app.media.clearImages()
            app.privacy.hidePreviews()
            clearDrafts()
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
        }
        previousUid = uid
        app.outbox.items.value.filter { it.uid != uid }.forEach {
            WorkManager.getInstance(app).cancelUniqueWork("send:" + it.id)
            app.outbox.remove(it.id)
        }
        settings.edit().putString("uid", uid).apply()
        membership?.remove(); membership = null
        if (uid != null) {
            app.outbox.items.value.filter { it.uid == uid && it.state != "Failed" }.forEach { scheduleSend(app, it) }
            membership = database.collection("pairing").document(uid).addSnapshotListener(MetadataChanges.INCLUDE) { document, error ->
                if (auth.currentUser?.uid != uid) return@addSnapshotListener
                if (error != null) { app.media.clearImages(); app.privacy.hidePreviews(); return@addSnapshotListener }
                if (document == null || document.metadata.isFromCache) return@addSnapshotListener
                val coupleId = document.getString("coupleId")
                val old = settings.getString(uid + "_couple", null)
                if (old != null && old != coupleId) {
                    WorkManager.getInstance(app).cancelAllWorkByTag("couple:" + old)
                    app.outbox.clear(uid, old)
                    app.media.clearImages()
                    app.privacy.hidePreviews()
                    clearDrafts()
                    (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
                }
                settings.edit().putString(uid + "_couple", coupleId).apply()
            }
        }
    }
    init { auth.addAuthStateListener(listener) }
    private fun clearDrafts() {
        app.cacheDir.listFiles().orEmpty().filter { file ->
            file.name == "capture" || listOf("picked-", "preview-", "drawing-").any { file.name.startsWith(it) }
        }.forEach { it.deleteRecursively() }
    }
}
