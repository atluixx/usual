package com.okoda.usual

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

@Composable
fun PrivateImage(repository: MediaRepository, coupleId: String?, id: String, avatar: Boolean = false, full: Boolean = false, version: Int = 0, game: Boolean = false) {
    var attempt by remember(coupleId, id) { mutableStateOf(0) }
    var failed by remember(coupleId, id) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, coupleId, id, attempt, version) {
        try { value = repository.image(coupleId, id, thumbnail = !full, avatar = avatar, game = game, fresh = avatar || attempt > 0); failed = false }
        catch (_: UserFacingException) { failed = true }
        catch (_: Exception) { failed = true }
    }
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), if (avatar) "Private avatar" else "Private photo or drawing", Modifier.fillMaxWidth().heightIn(max = if (full) 420.dp else 160.dp), contentScale = ContentScale.Fit)
    else if (failed) TextButton(onClick = { attempt++ }) { Text("Image unavailable. Retry") }
    else CircularProgressIndicator()
}

@Composable
fun ContentComposer(model: MediaViewModel, avatar: Boolean = false, gameId: String? = null, gameVersion: Int? = null, startAction: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by model.state.collectAsStateWithLifecycle()
    var drawing by remember { mutableStateOf(startAction == "draw" || gameId != null) }
    var preview by remember { mutableStateOf<File?>(null) }
    var kind by remember { mutableStateOf("PHOTO") }
    var caption by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    DisposableEffect(Unit) { onDispose { preview?.delete() } }
    fun picked(uri: Uri) {
        scope.launch {
            try { preview = imageDraft(context, uri); kind = if (avatar) "AVATAR" else "PHOTO" }
            catch (cause: Exception) { error = (cause as? UserFacingException)?.message ?: "Couldn’t open the photo." }
            finally { cameraFile?.takeIf { uri.scheme == "file" && it.absolutePath == uri.path }?.delete() }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::picked) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = cameraFile
        if (success && file != null) picked(Uri.fromFile(file))
        else file?.delete()
    }
    LaunchedEffect(startAction) { if (startAction == "photo") picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    Column {
        if (avatar) {
            Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !state.busy) { Text("Choose avatar photo") }
        } else if (gameId == null) {
            Row {
                TextButton(onClick = { drawing = !drawing }) { Text(if (drawing) "Close canvas" else "Draw") }
                TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Photo") }
                TextButton(onClick = {
                    try {
                        val directory = File(context.cacheDir, "capture").apply { mkdirs() }
                        val file = File(directory, UUID.randomUUID().toString() + ".jpg")
                        cameraFile = file
                        camera.launch(FileProvider.getUriForFile(context, context.packageName + ".files", file))
                    } catch (_: Exception) { error = "Camera unavailable. Choose a photo instead." }
                }) { Text("Camera") }
            }
            if (!drawing) {
                OutlinedTextField(note, { note = it.take(280) }, label = { Text("A short message") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { model.queue("MESSAGE", note, onQueued = { note = "" }) }, enabled = !state.busy && note.isNotBlank()) { Text("Send message") }
            }
        }
        if (drawing && !avatar) DrawingCanvas { file -> kind = "DRAWING"; preview = file }
        error?.let { Text(it) }
        state.notice?.let { Text(it) }
    }
    preview?.let { file ->
        val bitmap = remember(file.path) { BitmapFactory.decodeFile(file.path) }
        AlertDialog(
            onDismissRequest = { if (!state.busy) { file.delete(); preview = null } },
            title = { Text(if (avatar) "Preview avatar" else "Preview before sending") },
            text = { Column {
                bitmap?.let { Image(it.asImageBitmap(), "Preview", Modifier.fillMaxWidth().heightIn(max = 280.dp)) }
                if (!avatar && gameId == null) OutlinedTextField(caption, { caption = it.take(280) }, label = { Text("Optional message") })
                state.notice?.let { Text(it) }
            } },
            confirmButton = { TextButton(enabled = !state.busy, onClick = {
                model.queue(kind, caption, file, gameId, gameVersion, onQueued = { preview = null; caption = ""; drawing = gameId != null })
            }) { Text(if (avatar) "Use avatar" else "Send privately") } },
            dismissButton = { TextButton(enabled = !state.busy, onClick = { file.delete(); preview = null }) { Text("Discard preview") } },
        )
    }
}

@Composable
fun PendingSends(model: MediaViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val entries by model.outbox.collectAsStateWithLifecycle()
    entries.filter { it.uid == state.uid }.forEach { item ->
        Text(item.kind.lowercase().replaceFirstChar(Char::uppercase) + ": " + item.state)
        item.error?.let { Text(it) }
        Row {
            if (item.state == "Failed") TextButton(onClick = { model.retry(item) }) { Text("Retry send") }
            TextButton(onClick = { model.discard(item) }) { Text("Discard pending send") }
        }
    }
}

@Composable
fun GalleryScreen(model: MediaViewModel, repository: MediaRepository) {
    val state by model.state.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<ContentItem?>(null) }
    Column {
        Row {
            FilterChip(selected = !state.savedOnly, onClick = { model.filter(false) }, label = { Text("History") })
            FilterChip(selected = state.savedOnly, onClick = { model.filter(true) }, label = { Text("Saved memories") })
        }
        if (state.coupleId == null) Text("Pair with your partner to share moments.")
        else LazyColumn {
            item {
                if (state.gallery.loading) CircularProgressIndicator()
                if (state.gallery.cached) Text("Cached items. Connect to refresh shared changes.")
                state.gallery.error?.let { Text(it) }
                state.notice?.let { Text(it) }
                TextButton(onClick = model::refresh) { Text("Refresh gallery") }
                if (!state.gallery.loading && state.gallery.items.isEmpty()) Text(if (state.savedOnly) "No saved memories yet." else "Your first shared moment will appear here.")
            }
            items(state.gallery.items, key = { it.id }) { item ->
                Column(Modifier.fillMaxWidth().clickable { model.select(item.id) }) {
                    if (item.kind != "MESSAGE") PrivateImage(repository, state.coupleId, item.id)
                    Text(item.caption.ifBlank { if (item.kind == "DRAWING") "A drawing for you" else "A shared photo" })
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.createdAt)))
                    Text(if (item.senderUid == state.uid) when { item.opened -> "Opened"; item.received -> "Received"; else -> "Sent" } else "From your partner")
                    Row {
                        TextButton(onClick = { model.save(item) }, enabled = !state.busy) { Text(if (item.saved) "Unsave" else "Save memory") }
                        TextButton(onClick = { deleting = item }, enabled = !state.busy) { Text("Delete for both") }
                    }
                    if (item.reactions.isNotEmpty()) Text(item.reactions.values.joinToString(" "))
                    Spacer(Modifier.height(16.dp))
                }
            }
            item {
                Row {
                    TextButton(onClick = model::latest) { Text("Latest") }
                    if (state.gallery.items.size == 20) TextButton(onClick = model::older) { Text("Older moments") }
                }
            }
        }
    }
    state.selected?.let { item ->
        AlertDialog(onDismissRequest = model::closeDetail, title = { Text("Your shared moment") },
            text = { Column {
                if (item.kind != "MESSAGE") PrivateImage(repository, state.coupleId, item.id, full = true)
                Text(item.caption)
                Row {
                    listOf("❤️", "😊", "✨").forEach { reaction -> TextButton(onClick = { model.react(item, reaction) }, enabled = !state.busy) { Text(reaction) } }
                    TextButton(onClick = { model.react(item, "") }, enabled = !state.busy) { Text("Clear") }
                }
            } }, confirmButton = { TextButton(onClick = model::closeDetail) { Text("Close") } })
    }
    deleting?.let { item ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete for both of you?") },
            text = { Text("This removes the item from history and saved memories for both people and schedules its media for deletion.") },
            confirmButton = { TextButton(onClick = { model.delete(item); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } })
    }
}
