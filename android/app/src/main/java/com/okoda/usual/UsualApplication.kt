package com.okoda.usual

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings

class UsualApplication : Application() {
    lateinit var repository: UsualRepository
        private set

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        val auth = FirebaseAuth.getInstance()
        val database = FirebaseFirestore.getInstance()
        database.firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .build()
        if (BuildConfig.USE_FIREBASE_EMULATORS) {
            check(BuildConfig.DEBUG)
            check(FirebaseApp.getInstance().options.projectId == "demo-usual")
            auth.useEmulator("10.0.2.2", 9099)
            database.useEmulator("10.0.2.2", 8080)
        }
        repository = FirebaseUsualRepository(auth, database)
    }
}
