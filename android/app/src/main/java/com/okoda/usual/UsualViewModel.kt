package com.okoda.usual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UsualUiState(
    val account: Account? = null,
    val profile: ProfileState = ProfileState.Missing,
    val busy: Boolean = false,
    val notice: String? = null,
)

class UsualViewModel(private val repository: UsualRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(UsualUiState())
    val state = mutableState.asStateFlow()
    private var actionJob: Job? = null
    private var stopProfile: (() -> Unit)? = null
    private val stopAccount = repository.observeAccount { account ->
        val changedUser = account?.uid != mutableState.value.account?.uid
        if (changedUser) {
            stopProfile?.invoke()
            stopProfile = null
            mutableState.update { it.copy(account = account, profile = ProfileState.Missing, notice = null) }
            if (account != null) observeProfile(account.uid)
        } else {
            mutableState.update { it.copy(account = account) }
        }
    }

    fun startGuest() = runAction { repository.startGuest() }

    fun linkGoogle(acquireIdToken: suspend () -> String) {
        val uid = mutableState.value.account?.uid ?: return
        if (mutableState.value.account?.googleLinked == true) return
        runAction {
            val token = acquireIdToken()
            if (mutableState.value.account?.uid != uid) return@runAction
            repository.linkGoogle(uid, token)
            show("Google linked. Your Usual profile stays the same and can be recovered on another device.")
        }
    }

    fun signInGoogle(acquireIdToken: suspend () -> String) {
        if (mutableState.value.account != null) return
        runAction { repository.signInGoogle(acquireIdToken()) }
    }

    fun signUp(email: String, password: String) {
        if (!validEmail(email)) return show("Enter a valid email address.")
        if (password.length < 6) return show("Use a password with at least 6 characters.")
        runAction { repository.signUp(email.trim(), password) }
    }

    fun signIn(email: String, password: String) {
        if (!validEmail(email) || password.isEmpty()) return show("Enter your email and password.")
        runAction { repository.signIn(email.trim(), password) }
    }

    fun resetPassword(email: String) {
        if (!validEmail(email)) return show("Enter your email address first.")
        runAction {
            repository.resetPassword(email.trim())
            show("If this email can receive a password reset, a link has been sent.")
        }
    }

    fun sendVerification() = runAction {
        repository.sendVerification()
        show("Verification email sent. Open the link, then refresh your account here.")
    }

    fun refresh() {
        val uid = mutableState.value.account?.uid ?: return
        stopProfile?.invoke()
        observeProfile(uid)
        runAction {
            repository.refreshAccount()
            repository.refreshProfile(uid)
        }
    }

    fun saveProfile(displayName: String) {
        val uid = mutableState.value.account?.uid ?: return
        val normalized = displayName.trim()
        if (normalized.isEmpty() || normalized.length > 80) return show("Use a display name with 1–80 characters.")
        runAction {
            repository.saveProfile(uid, normalized)
            show("Profile saved.")
        }
    }

    fun signOut() {
        if (mutableState.value.account?.isGuest == true) {
            show("Link Google before signing out so you can recover this guest profile.")
            return
        }
        actionJob?.cancel()
        repository.signOut()
        mutableState.update { it.copy(busy = false, notice = null) }
    }

    private fun observeProfile(uid: String) {
        stopProfile = repository.observeProfile(uid) { profile ->
            if (mutableState.value.account?.uid == uid) {
                mutableState.update { it.copy(profile = profile) }
            }
        }
    }

    private fun runAction(action: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, notice = null) }
        actionJob = viewModelScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                show((error as? UserFacingException)?.message ?: "That didn’t work. Please try again.")
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun validEmail(email: String): Boolean {
        val value = email.trim()
        return value.length in 3..254 && value.contains('@') && value.none(Char::isWhitespace)
    }

    private fun show(message: String) = mutableState.update { it.copy(notice = message) }

    override fun onCleared() {
        stopProfile?.invoke()
        stopAccount()
        super.onCleared()
    }
}
