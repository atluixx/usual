package com.okoda.usual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PairingUiState(val uid: String? = null, val couple: CoupleState = CoupleState(), val busy: Boolean = false, val notice: String? = null)

class CoupleViewModel(private val identity: UsualRepository, private val couples: CoupleRepository) : ViewModel() {
    private val mutable = MutableStateFlow(PairingUiState())
    val state = mutable.asStateFlow()
    private var stopCouple: (() -> Unit)? = null
    private val stopIdentity = identity.observeAccount { account ->
        if (account?.uid != mutable.value.uid) {
            stopCouple?.invoke()
            mutable.value.uid?.let { couples.clearCode(it) }
            mutable.value = PairingUiState(uid = account?.uid)
            if (account != null) observe(account.uid)
        }
    }
    fun code(): String? = mutable.value.uid?.let(couples::shownCode)
    fun create() {
        val uid = mutable.value.uid ?: return
        val (code, key) = couples.pendingCode(uid)
        action { couples.backend.call("pair.create", mapOf("code" to code), key) }
    }
    fun claim(code: String) {
        val normalized = code.filterNot { it == '-' || it.isWhitespace() }.uppercase()
        if (!normalized.matches(Regex("[A-F0-9]{24}"))) {
            mutable.update { it.copy(notice = "Enter the complete 24-character code.") }; return
        }
        action { couples.backend.call("pair.claim", mapOf("code" to normalized)) }
    }
    fun confirm() {
        val id = mutable.value.couple.invitationId ?: return
        action { couples.backend.call("pair.confirm", mapOf("invitationId" to id, "confirm" to true)) }
    }
    fun cancel() {
        val uid = mutable.value.uid ?: return
        val id = mutable.value.couple.invitationId ?: return
        action { couples.backend.call("pair.cancel", mapOf("invitationId" to id)); couples.clearCode(uid) }
    }
    fun unpair() {
        val id = mutable.value.couple.coupleId ?: return
        action { couples.backend.call("pair.end", mapOf("coupleId" to id, "confirm" to true)) }
    }
    fun deleteAccount() = action {
        couples.backend.call("identity.delete", mapOf("confirm" to true))
        identity.signOut()
    }
    fun refresh() {
        val uid = mutable.value.uid ?: return
        stopCouple?.invoke(); observe(uid)
    }
    private fun observe(uid: String) {
        stopCouple = couples.observe(uid) { value ->
            if (mutable.value.uid == uid) {
                if (mutable.value.couple.invitationId != null && value.invitationId == null) couples.clearCode(uid)
                mutable.update { it.copy(couple = value) }
            }
        }
    }
    private fun action(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        val uid = mutable.value.uid
        mutable.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (mutable.value.uid == uid) mutable.update { it.copy(notice = (error as? UserFacingException)?.message ?: "Couldn’t finish. Retry.") } }
            finally { if (mutable.value.uid == uid) mutable.update { it.copy(busy = false) } }
        }
    }
    override fun onCleared() { stopCouple?.invoke(); stopIdentity(); super.onCleared() }
}
