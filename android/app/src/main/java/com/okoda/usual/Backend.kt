package com.okoda.usual

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

class Backend(context: Context, private val functions: FirebaseFunctions, private val auth: FirebaseAuth) {
    private val journal = context.getSharedPreferences("pending_actions", Context.MODE_PRIVATE)

    suspend fun call(op: String, data: Map<String, Any?> = emptyMap(), requestId: String? = null): Map<String, Any?> {
        val uid = auth.currentUser?.uid ?: throw UserFacingException("Start a guest session or sign in.")
        val digest = MessageDigest.getInstance("SHA-256").digest(JSONObject(data.toSortedMap()).toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val journalKey = uid + ":" + op + ":" + digest
        val key = requestId ?: synchronized(journal) {
            journal.getString(journalKey, null) ?: UUID.randomUUID().toString().also {
                check(journal.edit().putString(journalKey, it).commit())
            }
        }
        val payload = mapOf("v" to 1, "op" to op, "requestId" to key, "data" to data)
        for (attempt in 0..2) {
            if (auth.currentUser?.uid != uid) throw UserFacingException("Your session changed. Please retry.")
            try {
                @Suppress("UNCHECKED_CAST")
                val result = functions.getHttpsCallable("api").call(payload).await().data as? Map<String, Any?>
                    ?: throw UserFacingException("The response could not be read. Refresh and retry.")
                if (requestId == null) journal.edit().remove(journalKey).apply()
                return result
            } catch (error: FirebaseFunctionsException) {
                if (attempt < 2 && error.code in listOf(FirebaseFunctionsException.Code.UNAVAILABLE, FirebaseFunctionsException.Code.DEADLINE_EXCEEDED)) {
                    delay(500L * (attempt + 1))
                    continue
                }
                if (error.code == FirebaseFunctionsException.Code.UNAUTHENTICATED) auth.signOut()
                val safeMessage = (error.details as? Map<*, *>)?.get("safeMessage") as? String
                throw UserFacingException(safeMessage?.take(200) ?: when (error.code) {
                    FirebaseFunctionsException.Code.UNAVAILABLE, FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                        "Couldn’t connect. Check your connection and retry."
                    FirebaseFunctionsException.Code.UNAUTHENTICATED -> "This session ended. Sign in again or start a guest session."
                    FirebaseFunctionsException.Code.NOT_FOUND -> "The backend is not deployed yet. Use the local emulators or complete deployment."
                    FirebaseFunctionsException.Code.PERMISSION_DENIED -> "This item or couple is no longer available."
                    else -> "This action could not finish. Refresh and retry."
                })
            }
        }
        throw UserFacingException("Please retry.")
    }
}
