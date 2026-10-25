import { randomInt, randomUUID } from "node:crypto";
import { Timestamp, Transaction } from "firebase-admin/firestore";
import {
  activeCouple,
  Args,
  db,
  fail,
  fields,
  hash,
  id,
  integer,
  now,
  once,
  receipt,
  Result,
  text,
} from "./core.js";
import { event } from "./content.js";
import {
  mediaResponse,
  prepareMedia,
  removePending,
  sourceHash,
} from "./media.js";
import { consumeUpload } from "./uploads.js";

const prompts = [
  "cat",
  "moon",
  "bicycle",
  "sunflower",
  "umbrella",
  "rainbow",
  "castle",
  "pizza",
  "penguin",
  "hot air balloon",
  "guitar",
  "lighthouse",
];
export const matchRef = (coupleId: string, matchId: string) =>
  db.doc("couples/" + coupleId + "/matches/" + matchId);
const secretRef = (coupleId: string, matchId: string) =>
  db.doc("couples/" + coupleId + "/secrets/" + matchId);
export async function visibleMatch(
  tx: Transaction,
  uid: string,
  coupleId: string,
  matchId: string,
): Promise<Args> {
  await activeCouple(tx, uid, coupleId);
  const match = (await tx.get(matchRef(coupleId, matchId))).data();
  if (!match || ![match.inviterUid, match.recipientUid].includes(uid))
    return fail("not-found", "This game is not available.");
  if (match.rulesVersion !== 1)
    return fail("failed-precondition", "Update Usual to open this game.");
  return match;
}
function version(match: Args, data: Args): number {
  if (integer(data, "expectedVersion", 1, 1000) !== match.version)
    return fail(
      "failed-precondition",
      "The game changed. Refresh before acting.",
    );
  return (match.version as number) + 1;
}
function drawingTurn(match: Args, uid: string, data: Args): number {
  const next = version(match, data);
  if (
    match.kind !== "DRAWGUESS" ||
    match.phase !== "DRAWING" ||
    match.inviterUid !== uid
  )
    return fail(
      "permission-denied",
      "Only the drawer can submit this drawing now.",
    );
  return next;
}
function recordMove(
  tx: Transaction,
  uid: string,
  coupleId: string,
  matchId: string,
  key: string,
  op: string,
  next: number,
  cell: number | null = null,
) {
  tx.create(
    matchRef(coupleId, matchId)
      .collection("moves")
      .doc(hash(uid + ":" + key)),
    { actorUid: uid, kind: op, version: next, cell, createdAt: now() },
  );
}
function gameEvent(
  tx: Transaction,
  recipient: string,
  coupleId: string,
  matchId: string,
  next: number,
  kind: string,
) {
  event(
    tx,
    "game_" + hash(matchId + ":" + next).slice(0, 32),
    recipient,
    coupleId,
    kind,
    matchId,
    next,
  );
}
export async function games(
  uid: string,
  op: string,
  key: string,
  data: Args,
): Promise<Result> {
  const coupleId = id(data, "coupleId");
  if (op === "game.create") {
    fields(data, ["coupleId", "kind"]);
    const kind = text(data, "kind", 1, 12);
    if (!["TICTACTOE", "DRAWGUESS"].includes(kind))
      return fail("invalid-argument", "Unsupported game.");
    const matchId = randomUUID();
    return once(
      uid,
      op,
      key,
      data,
      async (tx) => {
        const couple = await activeCouple(tx, uid, coupleId);
        if (Number(couple.activeGameCount || 0) >= 10)
          return fail(
            "resource-exhausted",
            "Finish or cancel a game before inviting more.",
          );
        const recipient = (couple.memberUids as string[]).find(
          (value) => value !== uid,
        )!;
        tx.create(matchRef(coupleId, matchId), {
          kind,
          rulesVersion: 1,
          phase: "INVITED",
          inviterUid: uid,
          recipientUid: recipient,
          version: 1,
          board: Array(9).fill(""),
          turnUid: uid,
          winnerUid: null,
          result: null,
          drawingPath: null,
          drawingHash: null,
          createdAt: now(),
          updatedAt: now(),
          expiresAt: Timestamp.fromMillis(Date.now() + 86_400_000),
        });
        tx.update(db.doc("couples/" + coupleId), {
          activeGameCount: Number(couple.activeGameCount || 0) + 1,
        });
        gameEvent(tx, recipient, coupleId, matchId, 1, "game_invite");
        return { coupleId, matchId, version: 1 };
      },
      async (tx, result) => {
        await visibleMatch(tx, uid, coupleId, result.matchId as string);
      },
    );
  }
  const matchId = id(data, "matchId");
  const guard = async (tx: Transaction) => {
    await visibleMatch(tx, uid, coupleId, matchId);
  };
  if (op === "game.prompt") {
    fields(data, ["coupleId", "matchId"]);
    return db.runTransaction(async (tx) => {
      const match = await visibleMatch(tx, uid, coupleId, matchId);
      if (
        match.kind !== "DRAWGUESS" ||
        match.inviterUid !== uid ||
        !["DRAWING", "GUESSING"].includes(match.phase as string)
      )
        return fail(
          "permission-denied",
          "Only the drawer can see this prompt.",
        );
      const secret = (await tx.get(secretRef(coupleId, matchId))).data();
      if (!secret) return fail("not-found", "Prompt unavailable.");
      return { prompt: secret.prompt };
    });
  }
  if (op === "game.media") {
    fields(data, ["coupleId", "matchId", "thumbnail"]);
    if (data.thumbnail !== undefined && typeof data.thumbnail !== "boolean")
      return fail("invalid-argument", "Invalid thumbnail option.");
    const eligible = async (tx: Transaction) => {
      const match = await visibleMatch(tx, uid, coupleId, matchId);
      if (
        !["GUESSING", "COMPLETED"].includes(match.phase as string) ||
        typeof match.drawingPath !== "string"
      )
        return fail("not-found", "No drawing is available.");
      return match;
    };
    const match = await db.runTransaction(eligible);
    const result = await mediaResponse(
      match.drawingPath as string,
      data.thumbnail === true,
    );
    await db.runTransaction(eligible);
    return result;
  }
  if (op === "game.draw") {
    fields(data, [
      "coupleId",
      "matchId",
      "expectedVersion",
      "uploadId",
      "sha256",
    ]);
    const previous = await receipt(uid, op, key, data, guard);
    if (previous) return previous;
    await db.runTransaction(async (tx) => {
      drawingTurn(await visibleMatch(tx, uid, coupleId, matchId), uid, data);
    });
    const uploadId = id(data, "uploadId");
    const media = await prepareMedia(
      uid,
      uploadId,
      sourceHash(data),
      "private/couples/" +
        coupleId +
        "/games/" +
        matchId +
        "/" +
        hash(uid + ":" + key),
      "DRAWING",
      coupleId,
    );
    const result = await once(
      uid,
      op,
      key,
      data,
      async (tx) => {
        const match = await visibleMatch(tx, uid, coupleId, matchId);
        const next = drawingTurn(match, uid, data);
        const consume = await consumeUpload(
          tx,
          uid,
          uploadId,
          sourceHash(data),
          "DRAWING",
          coupleId,
        );
        tx.update(matchRef(coupleId, matchId), {
          phase: "GUESSING",
          version: next,
          drawingPath: media.path,
          drawingHash: media.sha256,
          turnUid: match.recipientUid,
          updatedAt: now(),
        });
        recordMove(tx, uid, coupleId, matchId, key, op, next);
        gameEvent(
          tx,
          match.recipientUid as string,
          coupleId,
          matchId,
          next,
          "game_turn",
        );
        consume();
        return { matchId, coupleId, version: next };
      },
      guard,
    );
    await removePending(uid, uploadId);
    return result;
  }
  if (op === "game.move")
    fields(data, ["coupleId", "matchId", "expectedVersion", "cell"]);
  else if (op === "game.guess")
    fields(data, ["coupleId", "matchId", "expectedVersion", "guess"]);
  else if (op === "game.accept" || op === "game.cancel")
    fields(data, ["coupleId", "matchId", "expectedVersion"]);
  else return fail("invalid-argument", "Unknown game operation.");
  const chosenPrompt = prompts[randomInt(prompts.length)];
  return once(
    uid,
    op,
    key,
    data,
    async (tx) => {
      const match = await visibleMatch(tx, uid, coupleId, matchId);
      const next = version(match, data);
      const couple = (await tx.get(db.doc("couples/" + coupleId))).data()!;
      const update: Args = { version: next, updatedAt: now() };
      let finished = false;
      const recipient =
        match.inviterUid === uid ? match.recipientUid : match.inviterUid;
      let eventKind = "game_update";
      let cell: number | null = null;
      const secret =
        op === "game.guess"
          ? (await tx.get(secretRef(coupleId, matchId))).data()
          : undefined;
      if (op === "game.accept") {
        if (
          match.phase !== "INVITED" ||
          uid !== match.recipientUid ||
          (match.expiresAt as Timestamp).toMillis() <= Date.now()
        )
          return fail(
            "failed-precondition",
            "This invitation can no longer be accepted.",
          );
        update.phase = match.kind === "TICTACTOE" ? "PLAYING" : "DRAWING";
        update.turnUid = match.inviterUid;
        if (match.kind === "DRAWGUESS")
          tx.create(secretRef(coupleId, matchId), { prompt: chosenPrompt });
        eventKind = "game_turn";
      } else if (op === "game.cancel") {
        if (
          ["COMPLETED", "CANCELLED", "EXPIRED"].includes(match.phase as string)
        )
          return fail("failed-precondition", "This game has already ended.");
        update.phase = "CANCELLED";
        update.drawingPath = null;
        finished = true;
        tx.delete(secretRef(coupleId, matchId));
        if (match.drawingPath)
          tx.set(
            db.doc("cleanupJobs/object_" + hash(match.drawingPath as string)),
            {
              kind: "object",
              path: match.drawingPath,
              attempts: 0,
              nextAttemptAt: now(),
            },
          );
      } else if (op === "game.move") {
        if (
          match.kind !== "TICTACTOE" ||
          match.phase !== "PLAYING" ||
          match.turnUid !== uid
        )
          return fail("failed-precondition", "Wait for your turn.");
        const board = [...(match.board as string[])];
        if (
          board.length !== 9 ||
          !board.every((value) => ["", "X", "O"].includes(value))
        )
          return fail("failed-precondition", "Game state is unavailable.");
        cell = integer(data, "cell", 0, 8);
        if (board[cell])
          return fail(
            "failed-precondition",
            "That square is already occupied.",
          );
        board[cell] = uid === match.inviterUid ? "X" : "O";
        const won = [
          [0, 1, 2],
          [3, 4, 5],
          [6, 7, 8],
          [0, 3, 6],
          [1, 4, 7],
          [2, 5, 8],
          [0, 4, 8],
          [2, 4, 6],
        ].some(
          (line) =>
            board[line[0]] !== "" &&
            line.every((position) => board[position] === board[line[0]]),
        );
        finished = won || board.every(Boolean);
        Object.assign(update, {
          board,
          turnUid: finished ? null : recipient,
          phase: finished ? "COMPLETED" : "PLAYING",
          winnerUid: won ? uid : null,
          result: won ? "WIN" : finished ? "DRAW" : null,
        });
        if (!finished) eventKind = "game_turn";
      } else {
        if (
          match.kind !== "DRAWGUESS" ||
          match.phase !== "GUESSING" ||
          uid !== match.recipientUid ||
          !secret
        )
          return fail("permission-denied", "Only the guesser can guess now.");
        const guess = text(data, "guess", 1, 80).trim();
        if (!guess) return fail("invalid-argument", "Write a guess.");
        const normalize = (value: string) =>
          value
            .normalize("NFKD")
            .replace(/[\u0300-\u036f]/g, "")
            .toLowerCase()
            .replace(/[^a-z0-9]+/g, " ")
            .trim();
        Object.assign(update, {
          phase: "COMPLETED",
          turnUid: null,
          guess,
          resultPrompt: secret.prompt,
          result:
            normalize(guess) === normalize(secret.prompt as string)
              ? "GUESSED"
              : "REVEALED",
        });
        tx.delete(secretRef(coupleId, matchId));
        finished = true;
      }
      tx.update(matchRef(coupleId, matchId), update);
      if (finished)
        tx.update(db.doc("couples/" + coupleId), {
          activeGameCount: Math.max(0, Number(couple.activeGameCount || 0) - 1),
        });
      recordMove(tx, uid, coupleId, matchId, key, op, next, cell);
      gameEvent(tx, recipient as string, coupleId, matchId, next, eventKind);
      return { coupleId, matchId, version: next };
    },
    guard,
  );
}
export async function expireGameInvitations(): Promise<void> {
  const entries = await db
    .collectionGroup("matches")
    .where("phase", "==", "INVITED")
    .where("expiresAt", "<=", now())
    .limit(30)
    .get();
  for (const entry of entries.docs)
    await db.runTransaction(async (tx) => {
      const match = (await tx.get(entry.ref)).data();
      const parent = entry.ref.parent.parent!;
      const couple = (await tx.get(parent)).data();
      if (
        !match ||
        match.phase !== "INVITED" ||
        match.expiresAt.toMillis() > Date.now()
      )
        return;
      tx.update(entry.ref, {
        phase: "EXPIRED",
        version: match.version + 1,
        updatedAt: now(),
      });
      if (couple)
        tx.update(parent, {
          activeGameCount: Math.max(0, (couple.activeGameCount || 0) - 1),
        });
    });
}
