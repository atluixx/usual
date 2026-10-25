package com.okoda.usual

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

class FirebaseUsualRepository(
    private val auth: FirebaseAuth,
    private val database: FirebaseFirestore,
) : UsualRepository {
    override fun observeAccount(onChanged: (Account?) -> Unit): () -> Unit {
        val listener = FirebaseAuth.IdTokenListener { firebaseAuth ->
            onChanged(firebaseAuth.currentUser?.let { Account(it.uid, it.email, it.isEmailVerified) })
        }
        auth.addIdTokenListener(listener)
        return { auth.removeIdTokenListener(listener) }
    }

    override fun observeProfile(uid: String, onChanged: (ProfileState) -> Unit): () -> Unit {
        onChanged(ProfileState.Loading)
        val listener = database.collection("users").document(uid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { document, error ->
                when {
                    error != null -> onChanged(ProfileState.Failed(messageFor(error)))
                    document == null -> onChanged(ProfileState.Failed("Couldn’t load your profile. Try again."))
                    !document.exists() && document.metadata.isFromCache ->
                        onChanged(ProfileState.Failed("Connect to the internet to check your profile, then refresh."))
                    !document.exists() -> onChanged(ProfileState.Missing)
                    document.getString("displayName") == null ->
                        onChanged(ProfileState.Failed("Your profile needs attention. Try signing in again."))
                    else -> onChanged(
                        ProfileState.Ready(
                            Profile(
                                document.getString("displayName").orEmpty(),
                                document.metadata.hasPendingWrites(),
                                document.metadata.isFromCache,
                            ),
                        ),
                    )
                }
            }
        return { listener.remove() }
    }

    override suspend fun signUp(email: String, password: String) = safely {
        auth.createUserWithEmailAndPassword(email, password).await()
        Unit
    }

    override suspend fun signIn(email: String, password: String) = safely {
        auth.signInWithEmailAndPassword(email, password).await()
        Unit
    }

    override suspend fun resetPassword(email: String) = safely {
        try {
            auth.sendPasswordResetEmail(email).await()
        } catch (_: FirebaseAuthInvalidUserException) {
            // Keep the same response when an address has no account.
        }
        Unit
    }

    override suspend fun sendVerification() = safely {
        requireNotNull(auth.currentUser).sendEmailVerification().await()
        Unit
    }

    override suspend fun refreshAccount() = safely {
        val user = requireNotNull(auth.currentUser)
        user.reload().await()
        user.getIdToken(true).await()
        Unit
    }

    override suspend fun saveProfile(uid: String, displayName: String) = safely {
        check(auth.currentUser?.uid == uid)
        val reference = database.collection("users").document(uid)
        database.runTransaction { transaction ->
            val existing = transaction.get(reference)
            if (existing.exists()) {
                transaction.update(
                    reference,
                    "displayName", displayName,
                    "updatedAt", FieldValue.serverTimestamp(),
                )
            } else {
                transaction.set(
                    reference,
                    mapOf(
                        "uid" to uid,
                        "displayName" to displayName,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                        "schemaVersion" to 1,
                    ),
                )
            }
        }.await()
        Unit
    }

    override suspend fun refreshProfile(uid: String) = safely {
        check(auth.currentUser?.uid == uid)
        database.collection("users").document(uid).get(Source.SERVER).await()
        Unit
    }

    override fun signOut() = auth.signOut()

    private suspend fun <T> safely(action: suspend () -> T): T = try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw UserFacingException(messageFor(error))
    }

    private fun messageFor(error: Exception): String = when (error) {
        is FirebaseNetworkException -> "Couldn’t connect. Check your internet connection and try again."
        is FirebaseTooManyRequestsException -> "Too many attempts. Wait a while before trying again."
        is FirebaseAuthWeakPasswordException -> "Choose a stronger password and try again."
        is FirebaseAuthInvalidCredentialsException, is FirebaseAuthInvalidUserException ->
            "Couldn’t sign in. Check your email and password."
        is FirebaseAuthUserCollisionException ->
            "Couldn’t create this account. Try signing in or resetting your password."
        is FirebaseFirestoreException -> when (error.code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                "Your session can’t access this profile. Try signing in again."
            FirebaseFirestoreException.Code.UNAVAILABLE ->
                "Your profile couldn’t connect. Check your internet connection and retry."
            else -> "Your profile couldn’t be saved or loaded. Please retry."
        }
        else -> "That didn’t work. Please try again."
    }
}
