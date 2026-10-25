import assert from "node:assert/strict";
import { beforeEach, test } from "node:test";
import { randomBytes, randomUUID } from "node:crypto";
import { Timestamp } from "firebase-admin/firestore";
import sharp from "sharp";
import { execute } from "../lib/index.js";
import { db, hash } from "../lib/core.js";
import { mediaBucket } from "../lib/media.js";
import { dispatch } from "../lib/notifications.js";
assert.equal(process.env.GCLOUD_PROJECT, "demo-usual");
const call = (uid, op, data, requestId = randomUUID()) =>
  execute(uid, { v: 1, op, requestId, data });
const prefs = (uid, enabled = true) =>
  call(uid, "preferences.set", {
    contentNotifications: enabled,
    gameNotifications: enabled,
    widgetPreview: enabled,
  });
async function pair() {
  const code = randomBytes(12).toString("hex");
  const invitation = await call("alice", "pair.create", { code });
  await call("bob", "pair.claim", { code });
  return (
    await call("alice", "pair.confirm", {
      invitationId: invitation.invitationId,
      confirm: true,
    })
  ).coupleId;
}
const fid = "c" + "a".repeat(21);
beforeEach(async () => {
  for (const collection of await db.listCollections())
    await db.recursiveDelete(collection);
  await mediaBucket().deleteFiles({ force: true });
});
test("notification resolution is owner-only, opt-in and current-resource authorized", async () => {
  const coupleId = await pair();
  const item = await call("alice", "content.send", {
    coupleId,
    kind: "MESSAGE",
    caption: "Private words",
  });
  const eventId = item.contentId;
  await assert.rejects(call("bob", "notification.resolve", { eventId }), {
    code: "not-found",
  });
  await prefs("bob");
  assert.equal(
    (await call("bob", "notification.resolve", { eventId })).resourceId,
    eventId,
  );
  await assert.rejects(call("alice", "notification.resolve", { eventId }), {
    code: "not-found",
  });
  await call("bob", "content.delete", { coupleId, contentId: eventId });
  await assert.rejects(call("bob", "notification.resolve", { eventId }), {
    code: "not-found",
  });
});
test("transactional outbox retries failures, deduplicates accepted sends and carries no private content", async () => {
  const coupleId = await pair();
  await prefs("bob");
  await call("bob", "device.register", { fid });
  const { contentId } = await call("alice", "content.send", {
    coupleId,
    kind: "MESSAGE",
    caption: "Keep out of push",
  });
  const ref = db.doc("deliveryEvents/" + contentId);
  await dispatch(contentId, async () => {
    throw new Error("temporary transport failure");
  });
  assert.equal((await ref.get()).data().state, "pending");
  await ref.update({ nextAttemptAt: Timestamp.fromMillis(0) });
  let sends = 0;
  const send = async (message) => {
    sends++;
    assert.deepEqual(message.data, { eventId: contentId, recipientUid: "bob" });
    assert.equal(message.notification, undefined);
    return {
      successCount: 1,
      failureCount: 0,
      responses: [{ success: true, messageId: "test-only" }],
    };
  };
  await Promise.all([dispatch(contentId, send), dispatch(contentId, send)]);
  await dispatch(contentId, send);
  assert.equal(sends, 1);
  assert.equal((await ref.get()).data().state, "accepted");
});
test("old game versions, disabled preferences and unpairing suppress delayed notifications", async () => {
  const coupleId = await pair();
  await prefs("alice");
  await prefs("bob");
  await call("bob", "device.register", { fid });
  const game = await call("alice", "game.create", {
    coupleId,
    kind: "TICTACTOE",
  });
  const eventId = "game_" + hash(game.matchId + ":1").slice(0, 32);
  await call("bob", "game.accept", {
    coupleId,
    matchId: game.matchId,
    expectedVersion: 1,
  });
  await dispatch(eventId, async () => {
    throw new Error("must not send");
  });
  assert.equal(
    (await db.doc("deliveryEvents/" + eventId).get()).data().state,
    "suppressed",
  );
  const item = await call("alice", "content.send", {
    coupleId,
    kind: "MESSAGE",
    caption: "A moment",
  });
  await call("alice", "pair.end", { coupleId, confirm: true });
  assert.equal(
    (await db.doc("preferences/bob").get()).data().widgetPreview,
    false,
  );
  assert.equal(
    (await db.doc("preferences/alice").get()).data().widgetPreview,
    false,
  );
  await dispatch(item.contentId, async () => {
    throw new Error("must not send");
  });
  assert.equal(
    (await db.doc("deliveryEvents/" + item.contentId).get()).data().state,
    "suppressed",
  );
});
test("device token ownership and quotas are enforced; invalid tokens are pruned", async () => {
  const coupleId = await pair();
  await prefs("bob");
  await call("bob", "device.register", { fid });
  await assert.rejects(call("alice", "device.register", { fid }), {
    code: "permission-denied",
  });
  await assert.rejects(call("alice", "device.unregister", { fid }), {
    code: "permission-denied",
  });
  for (let index = 0; index < 4; index++)
    await call("bob", "device.register", {
      fid: "c" + String(index).repeat(21),
    });
  await assert.rejects(
    call("bob", "device.register", { fid: "c" + "Z".repeat(21) }),
    { code: "resource-exhausted" },
  );
  const item = await call("alice", "content.send", {
    coupleId,
    kind: "MESSAGE",
    caption: "A moment",
  });
  await dispatch(item.contentId, async (message) => ({
    successCount: 0,
    failureCount: message.fids.length,
    responses: message.fids.map(() => ({
      success: false,
      error: { code: "messaging/installation-id-not-registered" },
    })),
  }));
  assert.equal((await db.collection("devices").get()).size, 0);
});
test("memory widget requires opt-in and selects received live images, excluding messages and own sends", async () => {
  const coupleId = await pair();
  assert.deepEqual(await call("bob", "widget.snapshot", {}), {
    visible: false,
  });
  await prefs("bob");
  const bytes = await sharp({
    create: { width: 24, height: 24, channels: 4, background: "#abc" },
  })
    .png()
    .toBuffer();
  const uploadId = randomUUID(),
    sha256 = hash(bytes);
  await call("alice", "media.authorize", {
    uploadId,
    sha256,
    bytes: bytes.length,
    kind: "DRAWING",
    coupleId,
  });
  await mediaBucket()
    .file("pending/alice/" + uploadId)
    .save(bytes, {
      resumable: false,
      metadata: { contentType: "image/png", metadata: { sha256 } },
    });
  const item = await call("alice", "content.send", {
    coupleId,
    uploadId,
    sha256,
    kind: "DRAWING",
  });
  await call("alice", "content.send", {
    coupleId,
    kind: "MESSAGE",
    caption: "Newer, but not an image",
  });
  const result = await call("bob", "widget.snapshot", {});
  assert.equal(result.visible, true);
  assert.equal(result.contentId, item.contentId);
  assert.ok(result.base64);
  assert.equal(result.url, undefined);
  await prefs("alice");
  assert.deepEqual(await call("alice", "widget.snapshot", {}), {
    visible: false,
  });
  await call("bob", "content.delete", { coupleId, contentId: item.contentId });
  assert.deepEqual(await call("bob", "widget.snapshot", {}), {
    visible: false,
  });
  await prefs("bob", false);
  assert.deepEqual(await call("bob", "widget.snapshot", {}), {
    visible: false,
  });
});
