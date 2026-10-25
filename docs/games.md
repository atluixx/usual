# Game contracts, version 1

Both games are private to the current active couple. One trusted API validates
every action and persists the authoritative match, move receipt and delivery
event in one transaction. Firestore listeners recover the current snapshot on
restart/reconnection; the client never submits a board/result. All mutations
carry a stable request ID and expectedVersion, except initial creation. Results
return matchId/coupleId/version; clients render the listener, not a returned old
snapshot. A replay rechecks current couple access. Client writes are denied.

## Lifecycles

Create a TICTACTOE or DRAWGUESS invitation. The other member may accept or
decline; the creator may cancel. Invitations expire after 24 hours. Either
member can cancel active play. Active matches have no inactivity expiry and
survive app restarts. Completed/cancelled match metadata remains until couple
deletion. At most ten unfinished matches are allowed per couple. Closing a
couple cancels access to all matches immediately and queues all
match/move/secret/media cleanup.

Tic-tac-toe uses inviter X and recipient O. Accept moves INVITED to PLAYING with
X first. Each move includes cell (0–8) and expectedVersion. The server checks
phase, actor, turn, empty cell and version, and computes win/draw/next turn.
Concurrent stale moves, occupied cells and out-of-turn moves are rejected.
Completion is COMPLETED with winnerUid or a draw. No leaderboard is collected.

Draw & Guess is one cooperative round: INVITED, DRAWING, GUESSING, COMPLETED. On
acceptance the server randomly selects a prompt, stored in a separate
client-denied secret document. Only the drawer (inviter) can request it while
drawing/waiting; the guesser cannot read it through match/move/listener APIs.
The drawer previews/uploads one validated drawing with a frozen match version.
The guesser has one nonblank guess (at most 80 characters); case, accents and
punctuation normalize before comparison. Completion reveals the prompt and
result to both. Cancellation removes prompt/image access and schedules its media
deletion. Rules version 1 fixes these defaults so future rule changes can
introduce a new version without silently changing an existing match.

## Operations

- game.create: coupleId, kind.
- game.accept, game.cancel: coupleId, matchId, expectedVersion.
- game.move: those fields plus cell.
- game.prompt: coupleId, matchId (drawer-only read).
- game.draw: coupleId, matchId, expectedVersion, uploadId, sha256. Uses the same
  owner-bound DRAWING upload permit and durable Android outbox as other
  drawings.
- game.guess: coupleId, matchId, expectedVersion, guess.
- game.media: coupleId, matchId, optional thumbnail. Requires an eligible game
  phase and revalidates access after loading private bytes; no public media URL.

Match list queries are couple-scoped, ordered by immutable creation time and
limited to 20. A deep link may open an older exact match. The prompt is never in
FCM data, notification text, widget state, logs or public match metadata before
completion. Game invite/turn/update events are durable refresh signals.
