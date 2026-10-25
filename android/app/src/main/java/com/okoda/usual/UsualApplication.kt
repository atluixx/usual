package com.okoda.usual

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage

class UsualApplication : Application() {
    lateinit var repository: UsualRepository
        private set
    lateinit var backend: Backend
        private set
    lateinit var couples: CoupleRepository
        private set
    lateinit var outbox: OutboxStore
        private set
    lateinit var media: MediaRepository
        private set
    private var session: PrivateSession? = null

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        val auth = FirebaseAuth.getInstance()
        val database = FirebaseFirestore.getInstance()
        val functions = FirebaseFunctions.getInstance("southamerica-east1")
        val storage = FirebaseStorage.getInstance()
        database.firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .build()
        if (BuildConfig.USE_FIREBASE_EMULATORS) {
            check(BuildConfig.DEBUG)
            check(FirebaseApp.getInstance().options.projectId == "demo-usual")
            auth.useEmulator("10.0.2.2", 9099)
            database.useEmulator("10.0.2.2", 8080)
            functions.useEmulator("10.0.2.2", 5001)
            storage.useEmulator("10.0.2.2", 9199)
        }
        repository = FirebaseUsualRepository(auth, database)
        backend = Backend(this, functions, auth)
        couples = CoupleRepository(this, database, backend)
        outbox = OutboxStore(this)
        media = MediaRepository(database, backend)
        session = PrivateSession(this, auth, database)
    }
}
