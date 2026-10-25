package com.okoda.usual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GameMatch(
    val id: String, val kind: String, val phase: String, val version: Int,
    val inviterUid: String, val recipientUid: String, val turnUid: String?,
    val board: List<String>, val winnerUid: String?, val result: String?,
    val resultPrompt: String?, val guess: String?, val drawing: Boolean,
)
data class GamesUiState(
    val uid: String? = null, val coupleId: String? = null, val loading: Boolean = false,
    val games: List<GameMatch> = emptyList(), val selected: GameMatch? = null,
    val cached: Boolean = false, val busy: Boolean = false, val notice: String? = null, val prompt: String? = null,
)
class GameViewModel(private val backend: Backend, pairing: StateFlow<PairingUiState>) : ViewModel() {
    private val database = FirebaseFirestore.getInstance()
    private val mutable = MutableStateFlow(GamesUiState())
    val state = mutable.asStateFlow()
    private var stopList: (() -> Unit)? = null
    private var stopMatch: (() -> Unit)? = null
    private var cursor: DocumentSnapshot? = null
    private var last: DocumentSnapshot? = null
    private fun parse(doc: DocumentSnapshot): GameMatch? {
        if (!doc.exists() || doc.getLong("rulesVersion") != 1L) return null
        return GameMatch(doc.id, doc.getString("kind") ?: return null, doc.getString("phase") ?: return null,
            doc.getLong("version")?.toInt() ?: return null, doc.getString("inviterUid").orEmpty(), doc.getString("recipientUid").orEmpty(),
            doc.getString("turnUid"), (doc.get("board") as? List<*>)?.filterIsInstance<String>().orEmpty(),
            doc.getString("winnerUid"), doc.getString("result"), doc.getString("resultPrompt"), doc.getString("guess"), doc.getString("drawingPath") != null)
    }
    init {
        viewModelScope.launch { pairing.collect { current ->
            if (current.uid != mutable.value.uid || current.couple.coupleId != mutable.value.coupleId) {
                stopList?.invoke(); stopMatch?.invoke(); cursor = null
                mutable.value = GamesUiState(uid = current.uid, coupleId = current.couple.coupleId)
                refresh()
            }
        } }
    }
    fun refresh() {
        val uid = mutable.value.uid ?: return
        val coupleId = mutable.value.coupleId ?: return
        stopList?.invoke(); mutable.update { it.copy(loading = true, notice = null) }
        var query: Query = database.collection("couples").document(coupleId).collection("matches").orderBy("createdAt", Query.Direction.DESCENDING).limit(20)
        cursor?.let { query = query.startAfter(it) }
        val listener = query.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (mutable.value.uid == uid && mutable.value.coupleId == coupleId) {
                last = snapshot?.documents?.lastOrNull()
                mutable.update { it.copy(loading = false, games = snapshot?.documents?.mapNotNull(::parse).orEmpty(), cached = snapshot?.metadata?.isFromCache == true,
                    notice = if (error != null) "Games unavailable. Check your connection and pairing, then refresh." else null) }
            }
        }
        stopList = { listener.remove() }
    }
    fun older() { cursor = last; refresh() }
    fun latest() { cursor = null; refresh() }
    fun select(id: String) {
        val uid = mutable.value.uid ?: return
        val coupleId = mutable.value.coupleId ?: return
        stopMatch?.invoke(); mutable.update { it.copy(selected = null, prompt = null, loading = true) }
        val listener = database.collection("couples").document(coupleId).collection("matches").document(id).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (mutable.value.uid == uid && mutable.value.coupleId == coupleId) {
                val match = snapshot?.let(::parse)
                mutable.update { it.copy(selected = match, loading = false, cached = snapshot?.metadata?.isFromCache == true,
                    notice = if (match == null || error != null) "This game is no longer available." else null,
                    prompt = if (match?.phase in listOf("DRAWING", "GUESSING")) it.prompt else null) }
                if (match?.kind == "DRAWGUESS" && match.inviterUid == uid && match.phase in listOf("DRAWING", "GUESSING") && mutable.value.prompt == null) loadPrompt(match)
            }
        }
        stopMatch = { listener.remove() }
    }
    fun close() { stopMatch?.invoke(); stopMatch = null; mutable.update { it.copy(selected = null, prompt = null) } }
    private fun loadPrompt(match: GameMatch) {
        val uid = mutable.value.uid ?: return
        val coupleId = mutable.value.coupleId ?: return
        viewModelScope.launch {
            try {
                val result = backend.call("game.prompt", mapOf("coupleId" to coupleId, "matchId" to match.id), expectedUid = uid)
                if (mutable.value.uid == uid && mutable.value.coupleId == coupleId && mutable.value.selected?.id == match.id && mutable.value.selected?.phase in listOf("DRAWING", "GUESSING")) mutable.update { it.copy(prompt = result["prompt"] as? String) }
            } catch (_: UserFacingException) { if (mutable.value.selected?.id == match.id) mutable.update { it.copy(notice = "Connect and reopen the game to load your prompt.") } }
        }
    }
    fun create(kind: String) = action("game.create", mapOf("kind" to kind), open = true)
    fun accept(match: GameMatch) = mutate("game.accept", match)
    fun cancel(match: GameMatch) = mutate("game.cancel", match)
    fun move(match: GameMatch, cell: Int) = mutate("game.move", match, mapOf("cell" to cell))
    fun guess(match: GameMatch, guess: String) = mutate("game.guess", match, mapOf("guess" to guess.trim()))
    private fun mutate(op: String, match: GameMatch, extra: Map<String, Any?> = emptyMap()) = action(op, mapOf("matchId" to match.id, "expectedVersion" to match.version) + extra)
    private fun action(op: String, args: Map<String, Any?>, open: Boolean = false) {
        val uid = mutable.value.uid ?: return
        val coupleId = mutable.value.coupleId ?: return
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try {
                val result = backend.call(op, args + ("coupleId" to coupleId), expectedUid = uid)
                if (open && mutable.value.uid == uid && mutable.value.coupleId == coupleId) (result["matchId"] as? String)?.let(::select)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (mutable.value.uid == uid && mutable.value.coupleId == coupleId) mutable.update { it.copy(notice = (error as? UserFacingException)?.message ?: "Please refresh and retry.") } }
            finally { if (mutable.value.uid == uid && mutable.value.coupleId == coupleId) mutable.update { it.copy(busy = false) } }
        }
    }
    override fun onCleared() { stopList?.invoke(); stopMatch?.invoke(); super.onCleared() }
}
