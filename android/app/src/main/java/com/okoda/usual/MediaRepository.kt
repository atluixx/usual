package com.okoda.usual

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.security.MessageDigest

data class ContentItem(
    val id: String, val kind: String, val caption: String, val senderUid: String,
    val receiverUid: String, val saved: Boolean, val createdAt: Long,
    val received: Boolean, val opened: Boolean, val reactions: Map<String, String>,
)
data class GalleryState(val loading: Boolean = true, val items: List<ContentItem> = emptyList(), val cached: Boolean = false, val error: String? = null, val cursor: DocumentSnapshot? = null)

class MediaRepository(private val database: FirebaseFirestore, private val backend: Backend) {
    private val images = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val requests = Semaphore(2)
    fun clearImages() = images.evictAll()
    private fun item(document: DocumentSnapshot): ContentItem? {
        if (!document.exists() || document.getBoolean("deleted") != false) return null
        return ContentItem(document.id, document.getString("kind") ?: return null, document.getString("caption").orEmpty(),
            document.getString("senderUid").orEmpty(), document.getString("receiverUid").orEmpty(), document.getBoolean("saved") == true,
            document.getTimestamp("createdAt")?.toDate()?.time ?: 0, document.getTimestamp("receivedAt") != null, document.getTimestamp("openedAt") != null,
            (document.get("reactions") as? Map<*, *>)?.entries?.mapNotNull { (key, value) -> if (key is String && value is String) key to value else null }?.toMap().orEmpty())
    }
    fun observeGallery(coupleId: String, saved: Boolean, after: DocumentSnapshot?, changed: (GalleryState) -> Unit): () -> Unit {
        var query: Query = database.collection("couples").document(coupleId).collection("content").whereEqualTo("deleted", false)
        if (saved) query = query.whereEqualTo("saved", true)
        query = query.orderBy("createdAt", Query.Direction.DESCENDING).limit(20)
        if (after != null) query = query.startAfter(after)
        val listener = query.addSnapshotListener(MetadataChanges.INCLUDE) { result, error ->
            if (error != null) { clearImages(); changed(GalleryState(loading = false, error = "Gallery unavailable. Check your connection or pairing, then refresh.")) }
            else if (result != null) changed(GalleryState(false, result.documents.mapNotNull(::item), result.metadata.isFromCache, cursor = result.documents.lastOrNull()))
        }
        return { listener.remove() }
    }
    fun observeItem(coupleId: String, id: String, changed: (ContentItem?) -> Unit): () -> Unit {
        val listener = database.collection("couples").document(coupleId).collection("content").document(id).addSnapshotListener { document, _ ->
            changed(document?.let(::item))
        }
        return { listener.remove() }
    }
    suspend fun image(coupleId: String?, contentId: String, thumbnail: Boolean = true, avatar: Boolean = false, game: Boolean = false, fresh: Boolean = false): Bitmap = requests.withPermit {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: throw UserFacingException("Start a session.")
        val key = uid + ":" + coupleId + ":" + contentId + ":" + thumbnail + ":" + avatar + ":" + game
        if (!fresh) images.get(key)?.let { return@withPermit it }
        val result = if (avatar) backend.call("profile.avatarMedia", mapOf("ownerUid" to contentId, "thumbnail" to thumbnail), expectedUid = uid)
            else if (game) backend.call("game.media", mapOf("coupleId" to coupleId, "matchId" to contentId, "thumbnail" to thumbnail), expectedUid = uid)
            else backend.call("content.media", mapOf("coupleId" to coupleId, "contentId" to contentId, "thumbnail" to thumbnail), expectedUid = uid)
        val bytes = Base64.decode(result["base64"] as? String ?: throw UserFacingException("Image unavailable."), Base64.DEFAULT)
        if (bytes.size > 4 * 1024 * 1024) throw UserFacingException("Image too large.")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (hash != result["sha256"]) throw UserFacingException("Image verification failed. Refresh.")
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw UserFacingException("Image could not be displayed.")
        if (FirebaseAuth.getInstance().currentUser?.uid != uid) throw UserFacingException("Your session changed.")
        images.put(key, bitmap)
        bitmap
    }
}
