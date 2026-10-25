package com.okoda.usual

data class Account(val uid: String, val email: String?, val emailVerified: Boolean)

data class Profile(
    val displayName: String,
    val pendingWrite: Boolean = false,
    val fromCache: Boolean = false,
)

sealed interface ProfileState {
    data object Loading : ProfileState
    data object Missing : ProfileState
    data class Ready(val profile: Profile) : ProfileState
    data class Failed(val message: String) : ProfileState
}

class UserFacingException(message: String) : Exception(message)

interface UsualRepository {
    fun observeAccount(onChanged: (Account?) -> Unit): () -> Unit
    fun observeProfile(uid: String, onChanged: (ProfileState) -> Unit): () -> Unit
    suspend fun signUp(email: String, password: String)
    suspend fun signIn(email: String, password: String)
    suspend fun resetPassword(email: String)
    suspend fun sendVerification()
    suspend fun refreshAccount()
    suspend fun saveProfile(uid: String, displayName: String)
    suspend fun refreshProfile(uid: String)
    fun signOut()
}
