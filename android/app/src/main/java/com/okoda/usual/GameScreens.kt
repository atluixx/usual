package com.okoda.usual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

fun gameTitle(kind: String) = if (kind == "TICTACTOE") "Tic-tac-toe" else "Draw & Guess"
@Composable
fun GamesScreen(model: GameViewModel, mediaModel: MediaViewModel, repository: MediaRepository) {
    val state by model.state.collectAsStateWithLifecycle()
    var cancelling by remember { mutableStateOf<GameMatch?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.coupleId == null) Text("Pair with your partner to play together.")
        else {
            if (state.loading) CircularProgressIndicator()
            if (state.cached) Text("Reconnecting… Showing the last received game state.")
            state.notice?.let { Text(it) }
            val match = state.selected
            if (match == null) {
                Text("Play together", style = MaterialTheme.typography.titleLarge)
                Text("A quick live game, or a drawing your partner can guess later.")
                Button(onClick = { model.create("TICTACTOE") }, enabled = !state.busy) { Text("Invite to tic-tac-toe") }
                Button(onClick = { model.create("DRAWGUESS") }, enabled = !state.busy) { Text("Invite to Draw & Guess") }
                TextButton(onClick = model::refresh) { Text("Refresh games") }
                if (!state.loading && state.games.isEmpty()) Text("No games yet. Invite your partner to begin.")
                state.games.forEach { game ->
                    OutlinedButton(onClick = { model.select(game.id) }, modifier = Modifier.fillMaxWidth()) { Text(gameTitle(game.kind) + " · " + game.phase.lowercase().replaceFirstChar(Char::uppercase)) }
                }
                Row {
                    TextButton(onClick = model::latest) { Text("Latest games") }
                    if (state.games.size == 20) TextButton(onClick = model::older) { Text("Older games") }
                }
            } else key(match.id) {
                TextButton(onClick = model::close) { Text("All games") }
                Text(gameTitle(match.kind), style = MaterialTheme.typography.titleLarge)
                when (match.phase) {
                    "INVITED" -> {
                        if (state.uid == match.recipientUid) Button(onClick = { model.accept(match) }, enabled = !state.busy && !state.cached) { Text("Accept game") }
                        else Text("Waiting for your partner to accept. Invitations last 24 hours.")
                    }
                    "PLAYING", "COMPLETED" -> {
                        if (match.kind == "TICTACTOE") {
                            Text(if (match.phase == "COMPLETED") when {
                                match.result == "DRAW" -> "A draw. Another round together?"
                                match.winnerUid == state.uid -> "You won this round!"
                                else -> "Your partner won this round!"
                            } else if (match.turnUid == state.uid) "Your turn" else "Your partner’s turn")
                            Text(if (match.inviterUid == state.uid) "You are X" else "You are O")
                            (0..2).forEach { row -> Row {
                                (0..2).forEach { column ->
                                    val cell = row * 3 + column
                                    OutlinedButton(onClick = { model.move(match, cell) }, enabled = !state.busy && !state.cached && match.phase == "PLAYING" && match.turnUid == state.uid && match.board.getOrNull(cell).isNullOrEmpty(), modifier = Modifier.size(88.dp)) {
                                        Text(match.board.getOrNull(cell).orEmpty().ifBlank { "·" }, style = MaterialTheme.typography.headlineMedium)
                                    }
                                }
                            } }
                        } else {
                            if (match.drawing) PrivateImage(repository, state.coupleId, match.id, game = true, full = true)
                            Text(if (match.result == "GUESSED") "You figured it out together!" else "The answer is revealed.")
                            Text("Prompt: " + match.resultPrompt.orEmpty())
                            Text("Guess: " + match.guess.orEmpty())
                        }
                    }
                    "DRAWING" -> if (match.inviterUid == state.uid) {
                        Text("Draw: " + (state.prompt ?: "Loading prompt…"))
                        Text("Your partner will see the drawing and make one guess when ready.")
                        if (state.prompt != null) ContentComposer(mediaModel, gameId = match.id, gameVersion = match.version)
                        PendingSends(mediaModel)
                    } else Text("Your partner is drawing. You can return later.")
                    "GUESSING" -> {
                        PrivateImage(repository, state.coupleId, match.id, game = true, full = true)
                        if (match.recipientUid == state.uid) {
                            var guess by remember { mutableStateOf("") }
                            OutlinedTextField(guess, { guess = it.take(80) }, label = { Text("Your guess") }, modifier = Modifier.fillMaxWidth())
                            Text("One guess, then you’ll see the answer together.")
                            Button(onClick = { model.guess(match, guess) }, enabled = !state.busy && !state.cached && guess.isNotBlank()) { Text("Send guess") }
                        } else Text("Waiting for your partner’s guess. Prompt: " + state.prompt.orEmpty())
                    }
                    else -> Text(if (match.phase == "EXPIRED") "This invitation expired. Start another game whenever you like." else "This game was cancelled.")
                }
                if (match.phase !in listOf("COMPLETED", "CANCELLED", "EXPIRED")) TextButton(onClick = { cancelling = match }, enabled = !state.busy) { Text(if (match.phase == "INVITED" && match.recipientUid == state.uid) "Decline game" else "Cancel game") }
            }
        }
    }
    cancelling?.let { match -> AlertDialog(onDismissRequest = { cancelling = null }, title = { Text("End this game?") },
        text = { Text("Both of you will see that this game ended. A game drawing will be removed.") },
        confirmButton = { TextButton(onClick = { model.cancel(match); cancelling = null }) { Text("End game") } },
        dismissButton = { TextButton(onClick = { cancelling = null }) { Text("Keep playing") } }) }
}
