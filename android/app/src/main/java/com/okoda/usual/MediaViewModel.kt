package com.okoda.usual

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class MediaUiState(
    val uid: String? = null, val coupleId: String? = null, val gallery: GalleryState = GalleryState(),
    val savedOnly: Boolean = false, val selected: ContentItem? = null, val busy: Boolean = false, val notice: String? = null,
)
class MediaViewModel(private val app: UsualApplication, pairing: StateFlow<PairingUiState>) : ViewModel() {
    private val mutable = MutableStateFlow(MediaUiState())
    val state = mutable.asStateFlow()
    val outbox = app.outbox.items
    private var stopGallery: (() -> Unit)? = null
    private var stopItem: (() -> Unit)? = null
    private var cursor: com.google.firebase.firestore.DocumentSnapshot? = null
    private val acknowledging = mutableSetOf<String>()
    init {
        viewModelScope.launch {
            pairing.collect { value ->
                if (value.uid != mutable.value.uid || value.couple.coupleId != mutable.value.coupleId) {
                    stopGallery?.invoke(); stopItem?.invoke(); cursor = null; acknowledging.clear()
                    app.media.clearImages()
                    mutable.value = MediaUiState(uid = value.uid, coupleId = value.couple.coupleId)
                    if (value.couple.coupleId != null) refresh()
                }
            }
        }
    }
    fun refresh() {
        val current = mutable.value
        val uid = current.uid ?: return
        val coupleId = current.coupleId ?: return
        stopGallery?.invoke()
        mutable.update { it.copy(gallery = GalleryState()) }
        stopGallery = app.media.observeGallery(coupleId, current.savedOnly, cursor) { gallery ->
            if (mutable.value.uid == uid && mutable.value.coupleId == coupleId) {
                mutable.update { it.copy(gallery = gallery) }
                app.media.clearImages()
                app.privacy.refreshWidgets()
                val ids = gallery.items.filter { it.receiverUid == uid && !it.received && it.id !in acknowledging }.map { it.id }
                if (ids.isNotEmpty()) {
                    acknowledging.addAll(ids)
                    viewModelScope.launch {
                        try { app.backend.call("content.ack", mapOf("coupleId" to coupleId, "ids" to ids, "opened" to false), expectedUid = uid) }
                        catch (_: UserFacingException) { /* Refresh retries receipts; they do not create content. */ }
                        finally { acknowledging.removeAll(ids.toSet()) }
                    }
                }
            }
        }
    }
    fun filter(saved: Boolean) { cursor = null; mutable.update { it.copy(savedOnly = saved) }; refresh() }
    fun older() { cursor = mutable.value.gallery.cursor; refresh() }
    fun latest() { cursor = null; refresh() }
    fun select(id: String) {
        val uid = mutable.value.uid ?: return
        val coupleId = mutable.value.coupleId ?: return
        stopItem?.invoke()
        stopItem = app.media.observeItem(coupleId, id) { value ->
            if (mutable.value.uid == uid && mutable.value.coupleId == coupleId) {
                mutable.update { it.copy(selected = value, notice = if (value == null) "This item is no longer available." else null) }
                if (value != null && value.receiverUid == uid && !value.opened) action {
                    app.backend.call("content.ack", mapOf("coupleId" to coupleId, "ids" to listOf(id), "opened" to true), expectedUid = uid)
                }
            }
        }
    }
    fun closeDetail() { stopItem?.invoke(); stopItem = null; mutable.update { it.copy(selected = null) } }
    fun save(item: ContentItem) = mutate("content.save", item, mapOf("saved" to !item.saved))
    fun react(item: ContentItem, reaction: String) = mutate("content.react", item, mapOf("reaction" to reaction))
    fun delete(item: ContentItem) = mutate("content.delete", item)
    private fun mutate(op: String, item: ContentItem, extra: Map<String, Any?> = emptyMap()) {
        val uid = mutable.value.uid ?: return
        val coupleId = mutable.value.coupleId ?: return
        action { app.backend.call(op, mapOf("coupleId" to coupleId, "contentId" to item.id) + extra, expectedUid = uid) }
    }
    fun queue(kind: String, caption: String, file: File? = null, gameId: String? = null, gameVersion: Int? = null, onQueued: (() -> Unit)? = null) {
        val uid = mutable.value.uid ?: return
        val coupleId = if (kind == "AVATAR") null else mutable.value.coupleId ?: return
        if (caption.length > 280 || kind == "MESSAGE" && caption.isBlank()) { mutable.update { it.copy(notice = "Write a message with 1–280 characters.") }; return }
        action {
            val send = withContext(Dispatchers.IO) { app.outbox.enqueue(uid, coupleId, kind, caption.trim(), file, gameId, gameVersion) }
            if (mutable.value.uid != uid || coupleId != null && mutable.value.coupleId != coupleId) { app.outbox.remove(send.id); return@action }
            file?.delete()
            scheduleSend(app, send)
            mutable.update { it.copy(notice = "Queued securely. Your send will retry when connected.") }
            onQueued?.invoke()
        }
    }
    fun retry(item: PendingSend) {
        app.outbox.update(item.copy(state = "Queued", error = null))
        scheduleSend(app, item)
    }
    fun discard(item: PendingSend) {
        WorkManager.getInstance(app).cancelUniqueWork("send:" + item.id)
        app.outbox.remove(item.id)
        viewModelScope.launch { try { app.backend.call("media.discard", mapOf("uploadId" to item.id), expectedUid = item.uid) } catch (_: UserFacingException) { /* Expiring permit/orphan cleanup remains authoritative. */ } }
    }
    private fun action(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        val uid = mutable.value.uid
        mutable.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (mutable.value.uid == uid) mutable.update { it.copy(notice = (error as? UserFacingException)?.message ?: "Please retry.") } }
            finally { if (mutable.value.uid == uid) mutable.update { it.copy(busy = false) } }
        }
    }
    override fun onCleared() { stopGallery?.invoke(); stopItem?.invoke(); super.onCleared() }
}
