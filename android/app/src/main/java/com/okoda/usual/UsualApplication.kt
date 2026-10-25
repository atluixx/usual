package com.okoda.usual

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.functions.FirebaseFunctions

class UsualApplication : Application() {
    lateinit var repository: UsualRepository
        private set
    lateinit var backend: Backend
        private set
    lateinit var couples: CoupleRepository
        private set

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        val auth = FirebaseAuth.getInstance()
        val database = FirebaseFirestore.getInstance()
        val functions = FirebaseFunctions.getInstance("southamerica-east1")
        database.firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .build()
        if (BuildConfig.USE_FIREBASE_EMULATORS) {
            check(BuildConfig.DEBUG)
            check(FirebaseApp.getInstance().options.projectId == "demo-usual")
            auth.useEmulator("10.0.2.2", 9099)
            database.useEmulator("10.0.2.2", 8080)
            functions.useEmulator("10.0.2.2", 5001)
        }
        repository = FirebaseUsualRepository(auth, database)
        backend = Backend(this, functions, auth)
        couples = CoupleRepository(this, database, backend)
    }
}
