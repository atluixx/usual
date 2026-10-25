package com.okoda.usual

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class PendingSend(
    val id: String, val uid: String, val coupleId: String?, val kind: String,
    val caption: String, val path: String?, val sha256: String?,
    val state: String = "Queued", val error: String? = null, val gameId: String? = null,
)

class OutboxStore(context: Context) {
    private val directory = File(context.filesDir, "outbox").apply { mkdirs() }
    private val mutable = MutableStateFlow(readAll())
    val items = mutable.asStateFlow()
    init { cleanLooseFiles() }
    @Synchronized private fun cleanLooseFiles() {
        val retained = mutable.value.mapNotNull { it.path }.toSet()
        directory.listFiles().orEmpty().filter { it.extension == "image" && it.absolutePath !in retained }.forEach { it.delete() }
    }
    @Synchronized private fun readAll(): List<PendingSend> = directory.listFiles().orEmpty()
        .filter { it.extension == "json" }.mapNotNull { file ->
            try {
                val value = JSONObject(AtomicFile(file).openRead().use { it.readBytes().toString(Charsets.UTF_8) })
                PendingSend(value.getString("id"), value.getString("uid"), value.optString("coupleId").takeIf { it.isNotBlank() }, value.getString("kind"),
                    value.optString("caption"), value.optString("path").takeIf { it.isNotBlank() }, value.optString("sha256").takeIf { it.isNotBlank() },
                    value.optString("state", "Queued"), value.optString("error").takeIf { it.isNotBlank() }, value.optString("gameId").takeIf { it.isNotBlank() })
            } catch (_: Exception) { null }
        }
    @Synchronized fun get(id: String): PendingSend? = mutable.value.firstOrNull { it.id == id }
    @Synchronized fun enqueue(uid: String, coupleId: String?, kind: String, caption: String, source: File?, gameId: String? = null): PendingSend {
        if (mutable.value.count { it.uid == uid } >= 20) throw UserFacingException("Finish or discard pending sends before adding more.")
        val id = UUID.randomUUID().toString()
        val target = source?.let { File(directory, id + ".image").also { output -> it.copyTo(output, overwrite = false) } }
        val hash = target?.inputStream()?.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        val value = PendingSend(id, uid, coupleId, kind, caption, target?.absolutePath, hash, gameId = gameId)
        write(value)
        return value
    }
    @Synchronized fun update(value: PendingSend) { if (get(value.id) != null) write(value) }
    @Synchronized private fun write(value: PendingSend) {
        val json = JSONObject().apply {
            put("id", value.id); put("uid", value.uid); put("coupleId", value.coupleId ?: ""); put("kind", value.kind)
            put("caption", value.caption); put("path", value.path ?: ""); put("sha256", value.sha256 ?: "")
            put("state", value.state); put("error", value.error ?: ""); put("gameId", value.gameId ?: "")
        }
        val atomic = AtomicFile(File(directory, value.id + ".json"))
        val stream = atomic.startWrite()
        try { stream.write(json.toString().toByteArray()); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
        mutable.value = readAll()
        cleanLooseFiles()
    }
    @Synchronized fun remove(id: String) {
        get(id)?.path?.let { File(it).delete() }
        AtomicFile(File(directory, id + ".json")).delete()
        mutable.value = readAll()
    }
    fun clear(uid: String, coupleId: String? = null) {
        mutable.value.filter { it.uid == uid && (coupleId == null || it.coupleId == coupleId) }.forEach { remove(it.id) }
    }
}
