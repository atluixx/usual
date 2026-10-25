import assert from "node:assert/strict";
import { beforeEach, test } from "node:test";
import { randomBytes, randomUUID } from "node:crypto";
import { Timestamp } from "firebase-admin/firestore";
import sharp from "sharp";
import { execute } from "../lib/index.js";
import { db, hash } from "../lib/core.js";
import { matchRef, expireGameInvitations } from "../lib/games.js";
import { mediaBucket } from "../lib/media.js";

assert.equal(process.env.GCLOUD_PROJECT, "demo-usual");
const call = (uid, op, data, requestId = randomUUID()) =>
  execute(uid, { v: 1, op, requestId, data });
async function pair(a = "alice", b = "bob") {
  const code = randomBytes(12).toString("hex");
  const invitation = await call(a, "pair.create", { code });
  await call(b, "pair.claim", { code });
  return (
    await call(a, "pair.confirm", {
      invitationId: invitation.invitationId,
      confirm: true,
    })
  ).coupleId;
}
async function game(kind = "TICTACTOE") {
  const coupleId = await pair();
  const created = await call("alice", "game.create", { coupleId, kind });
  await call("bob", "game.accept", {
    coupleId,
    matchId: created.matchId,
    expectedVersion: 1,
  });
  return { coupleId, matchId: created.matchId };
}
beforeEach(async () => {
  for (const collection of await db.listCollections())
    await db.recursiveDelete(collection);
  await mediaBucket().deleteFiles({ force: true });
});
test("tic-tac-toe validates turns, stale state, occupied cells and a consistent winner; retry creates one move", async () => {
  const ids = await game();
  await assert.rejects(
    call("bob", "game.move", { ...ids, expectedVersion: 2, cell: 0 }),
    { code: "failed-precondition" },
  );
  const key = randomUUID();
  const data = { ...ids, expectedVersion: 2, cell: 0 };
  const first = await call("alice", "game.move", data, key);
  assert.deepEqual(await call("alice", "game.move", data, key), first);
  await assert.rejects(
    call("bob", "game.move", { ...ids, expectedVersion: 2, cell: 4 }),
    { code: "failed-precondition" },
  );
  await assert.rejects(
    call("bob", "game.move", { ...ids, expectedVersion: 3, cell: 0 }),
    { code: "failed-precondition" },
  );
  for (const [index, cell] of [3, 1, 4, 2].entries())
    await call(index % 2 === 0 ? "bob" : "alice", "game.move", {
      ...ids,
      expectedVersion: 3 + index,
      cell,
    });
  const match = (await matchRef(ids.coupleId, ids.matchId).get()).data();
  assert.equal(match.phase, "COMPLETED");
  assert.equal(match.winnerUid, "alice");
  assert.equal(match.result, "WIN");
  assert.equal(
    (await matchRef(ids.coupleId, ids.matchId).collection("moves").get()).size,
    6,
  );
  assert.equal(
    (await db.doc("couples/" + ids.coupleId).get()).data().activeGameCount,
    0,
  );
  await assert.rejects(
    call("bob", "game.move", {
      ...ids,
      expectedVersion: match.version,
      cell: 8,
    }),
    { code: "failed-precondition" },
  );
});
test("conflicting moves serialize and a full board produces a draw", async () => {
  const ids = await game();
  const racing = await Promise.allSettled(
    [0, 1].map((cell) =>
      call("alice", "game.move", { ...ids, expectedVersion: 2, cell }),
    ),
  );
  assert.equal(racing.filter((r) => r.status === "fulfilled").length, 1);
  const accepted = (await matchRef(ids.coupleId, ids.matchId).get())
    .data()
    .board.findIndex(Boolean);
  // Restart a separate match; deterministic draw sequence also verifies state survives independent API calls.
  const next = await call("bob", "game.create", {
    coupleId: ids.coupleId,
    kind: "TICTACTOE",
  });
  const draw = { coupleId: ids.coupleId, matchId: next.matchId };
  await call("alice", "game.accept", { ...draw, expectedVersion: 1 });
  for (const [index, cell] of [0, 1, 2, 4, 3, 5, 7, 6, 8].entries())
    await call(index % 2 === 0 ? "bob" : "alice", "game.move", {
      ...draw,
      expectedVersion: index + 2,
      cell,
    });
  assert.equal(
    (await matchRef(draw.coupleId, draw.matchId).get()).data().result,
    "DRAW",
  );
  assert.ok([0, 1].includes(accepted));
});
test("Draw & Guess hides its prompt, persists an immutable drawing and completes exactly once", async () => {
  const ids = await game("DRAWGUESS");
  const prompt = (await call("alice", "game.prompt", ids)).prompt;
  assert.equal(
    (await matchRef(ids.coupleId, ids.matchId).get()).data().prompt,
    undefined,
  );
  await assert.rejects(call("bob", "game.prompt", ids), {
    code: "permission-denied",
  });
  const bytes = await sharp({
    create: { width: 32, height: 32, channels: 4, background: "#abc" },
  })
    .png()
    .toBuffer();
  const uploadId = randomUUID(),
    sha256 = hash(bytes),
    key = randomUUID();
  await call("alice", "media.authorize", {
    coupleId: ids.coupleId,
    uploadId,
    sha256,
    bytes: bytes.length,
    kind: "DRAWING",
  });
  await mediaBucket()
    .file("pending/alice/" + uploadId)
    .save(bytes, {
      resumable: false,
      metadata: { contentType: "image/png", metadata: { sha256 } },
    });
  const data = { ...ids, expectedVersion: 2, uploadId, sha256 };
  const result = await call("alice", "game.draw", data, key);
  assert.deepEqual(await call("alice", "game.draw", data, key), result);
  assert.equal(
    (
      await call("alice", "operation.status", {
        requestId: key,
        coupleId: ids.coupleId,
      })
    ).completed,
    true,
  );
  assert.ok((await call("bob", "game.media", ids)).base64);
  await call("bob", "game.guess", {
    ...ids,
    expectedVersion: 3,
    guess: "  " + prompt.toUpperCase() + "! ",
  });
  const match = (await matchRef(ids.coupleId, ids.matchId).get()).data();
  assert.equal(match.phase, "COMPLETED");
  assert.equal(match.result, "GUESSED");
  assert.equal(match.resultPrompt, prompt);
  assert.equal(
    (await db.doc("couples/" + ids.coupleId + "/secrets/" + ids.matchId).get())
      .exists,
    false,
  );
  await assert.rejects(
    call("bob", "game.guess", { ...ids, expectedVersion: 4, guess: prompt }),
    { code: "permission-denied" },
  );
});
test("invitations expire, cancellations are durable, outsiders and former members cannot access games", async () => {
  const coupleId = await pair();
  const { matchId } = await call("alice", "game.create", {
    coupleId,
    kind: "DRAWGUESS",
  });
  const ids = { coupleId, matchId };
  await assert.rejects(
    call("alice", "game.accept", { ...ids, expectedVersion: 1 }),
    { code: "failed-precondition" },
  );
  await pair("charlie", "dana");
  await assert.rejects(
    call("charlie", "game.accept", { ...ids, expectedVersion: 1 }),
    { code: "permission-denied" },
  );
  await matchRef(coupleId, matchId).update({
    expiresAt: Timestamp.fromMillis(Date.now() - 1000),
  });
  await assert.rejects(
    call("bob", "game.accept", { ...ids, expectedVersion: 1 }),
    { code: "failed-precondition" },
  );
  await expireGameInvitations();
  assert.equal(
    (await matchRef(coupleId, matchId).get()).data().phase,
    "EXPIRED",
  );
  const another = await call("bob", "game.create", {
    coupleId,
    kind: "TICTACTOE",
  });
  await call("alice", "game.cancel", {
    coupleId,
    matchId: another.matchId,
    expectedVersion: 1,
  });
  assert.equal(
    (await matchRef(coupleId, another.matchId).get()).data().phase,
    "CANCELLED",
  );
  await call("alice", "pair.end", { coupleId, confirm: true });
  await assert.rejects(call("bob", "game.media", ids), {
    code: "permission-denied",
  });
});
