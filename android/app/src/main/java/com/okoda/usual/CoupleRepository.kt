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
        var generation = 0
        var stopped = false
        val listener = database.collection("pairing").document(uid).addSnapshotListener(MetadataChanges.INCLUDE) { document, error ->
            if (stopped) return@addSnapshotListener
            val current = ++generation
            child?.remove()
            child = null
            if (error != null) {
                changed(CoupleState(status = "failed", error = "Couldn’t load pairing. Connect and refresh."))
            } else if (document == null || (!document.exists() && document.metadata.isFromCache)) {
                changed(CoupleState())
            } else {
                val coupleId = document.getString("coupleId")
                val invitationId = document.getString("invitationId")
                val base = CoupleState(document.getString("status") ?: "unpaired", coupleId, invitationId)
                changed(base)
                if (coupleId != null) {
                    clearCode(uid)
                    child = database.collection("couples").document(coupleId).addSnapshotListener { couple, failure ->
                        if (stopped || current != generation) return@addSnapshotListener
                        if (failure != null || couple == null || couple.getString("status") != "active") {
                            changed(CoupleState(status = "failed", error = "This couple is no longer available. Refresh."))
                        } else {
                            val members = couple.get("memberUids") as? List<*>
                            val partnerUid = members?.filterIsInstance<String>()?.firstOrNull { it != uid }
                            val names = couple.get("memberNames") as? Map<*, *>
                            changed(base.copy(partnerName = names?.get(partnerUid) as? String ?: "Your partner"))
                        }
                    }
                } else if (invitationId != null) {
                    child = database.collection("invitations").document(invitationId).addSnapshotListener { invite, failure ->
                        if (stopped || current != generation) return@addSnapshotListener
                        if (failure != null || invite == null) {
                            changed(base.copy(error = "Couldn’t refresh this invitation."))
                        } else {
                            changed(base.copy(
                                claimantName = invite.getString("claimantName"),
                                partnerName = invite.getString("creatorName") ?: "Your partner",
                                expiresAt = invite.getTimestamp("expiresAt")?.toDate()?.time,
                            ))
                        }
                    }
                }
            }
        }
        return { stopped = true; generation++; child?.remove(); listener.remove() }
    }
}
