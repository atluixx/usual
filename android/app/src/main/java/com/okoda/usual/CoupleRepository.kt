package com.okoda.usual

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import java.security.SecureRandom
import java.util.UUID

data class CoupleState(
    val status: String = "loading",
    val coupleId: String? = null,
    val invitationId: String? = null,
    val partnerName: String = "Your partner",
    val claimantName: String? = null,
    val expiresAt: Long? = null,
    val error: String? = null,
    val cached: Boolean = false,
)

class CoupleRepository(context: Context, private val database: FirebaseFirestore, val backend: Backend) {
    private val codes = context.getSharedPreferences("pairing_codes", Context.MODE_PRIVATE)

    fun pendingCode(uid: String): Pair<String, String> = synchronized(codes) {
        val code = codes.getString(uid + "_code", null)
        val key = codes.getString(uid + "_key", null)
        if (code != null && key != null && codes.getLong(uid + "_expires", 0) > System.currentTimeMillis()) return@synchronized code to key
        val bytes = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val created = bytes.joinToString("") { "%02X".format(it) } to UUID.randomUUID().toString()
        check(codes.edit().putString(uid + "_code", created.first).putString(uid + "_key", created.second).putLong(uid + "_expires", System.currentTimeMillis() + 20 * 60_000L).commit())
        created
    }
    fun shownCode(uid: String): String? = codes.getString(uid + "_code", null)
    fun clearCode(uid: String) { codes.edit().remove(uid + "_code").remove(uid + "_key").remove(uid + "_expires").apply() }

    fun observe(uid: String, changed: (CoupleState) -> Unit): () -> Unit {
        var child: ListenerRegistration? = null
        var target: String? = null
        var generation = 0
        var stopped = false
        var verified = false
        var rootCached = false
        var latest = CoupleState()
        fun publish(value: CoupleState) { latest = value; changed(value) }
        val listener = database.collection("pairing").document(uid).addSnapshotListener(MetadataChanges.INCLUDE) { document, error ->
            if (stopped) return@addSnapshotListener
            if (error != null) {
                generation++; child?.remove(); child = null; target = null
                publish(CoupleState(status = "failed", error = "Couldn’t load pairing. Connect and refresh."))
                return@addSnapshotListener
            }
            if (document == null || !verified && document.metadata.isFromCache) return@addSnapshotListener
            if (!document.metadata.isFromCache) verified = true
            rootCached = document.metadata.isFromCache
            val coupleId = document.getString("coupleId")
            val invitationId = document.getString("invitationId")
            val base = CoupleState(document.getString("status") ?: "unpaired", coupleId, invitationId, cached = rootCached)
            val nextTarget = coupleId?.let { "couples/" + it } ?: invitationId?.let { "invitations/" + it }
            // Memory cache uses eager GC. Replacing a listener on metadata-only changes
            // evicts its document and can falsely report membership loss while offline.
            if (target == nextTarget && child != null) {
                publish(latest.copy(cached = rootCached))
                return@addSnapshotListener
            }
            val current = ++generation
            child?.remove(); child = null; target = nextTarget
            if (nextTarget == null) { publish(base); return@addSnapshotListener }
            if (coupleId != null) { clearCode(uid); publish(CoupleState(cached = rootCached)) }
            else publish(base)
            var childVerified = false
            child = database.document(nextTarget).addSnapshotListener(MetadataChanges.INCLUDE) childListener@ { entry, failure ->
                if (stopped || current != generation) return@childListener
                if (failure != null) {
                    publish(CoupleState(status = "failed", error = "This pairing is no longer available. Refresh."))
                    return@childListener
                }
                if (entry == null || entry.metadata.isFromCache && (!childVerified || !entry.exists())) return@childListener
                if (!entry.metadata.isFromCache) childVerified = true
                if (coupleId != null) {
                    val members = (entry.get("memberUids") as? List<*>)?.filterIsInstance<String>().orEmpty()
                    if (entry.getString("status") != "active" || members.size != 2 || members.distinct().size != 2 || uid !in members) {
                        publish(CoupleState(status = "failed", error = "This couple is no longer available. Refresh."))
                    } else {
                        val names = entry.get("memberNames") as? Map<*, *>
                        publish(base.copy(partnerName = names?.get(members.first { it != uid }) as? String ?: "Your partner", cached = rootCached || entry.metadata.isFromCache))
                    }
                } else {
                    publish(base.copy(claimantName = entry.getString("claimantName"), partnerName = entry.getString("creatorName") ?: "Your partner", expiresAt = entry.getTimestamp("expiresAt")?.toDate()?.time, cached = rootCached || entry.metadata.isFromCache))
                }
            }
        }
        return { stopped = true; generation++; child?.remove(); listener.remove() }
    }
}
