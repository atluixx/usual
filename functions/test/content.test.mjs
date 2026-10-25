import assert from "node:assert/strict";
import { beforeEach, test } from "node:test";
import { randomBytes, randomUUID } from "node:crypto";
import sharp from "sharp";
import { execute } from "../lib/index.js";
import { db, hash } from "../lib/core.js";
import { mediaBucket, sweepOrphans } from "../lib/media.js";
import { cleanup } from "../lib/maintenance.js";

assert.equal(process.env.GCLOUD_PROJECT, "demo-usual");
assert.match(
  process.env.FIREBASE_STORAGE_EMULATOR_HOST || "",
  /^(127\.0\.0\.1|localhost):\d+$/,
);
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
async function upload(uid, coupleId, kind = "DRAWING", image = null) {
  const bytes =
    image ||
    (await sharp({
      create: { width: 32, height: 32, channels: 4, background: "#e76779" },
    })
      .png()
      .toBuffer());
  const uploadId = randomUUID();
  const sha256 = hash(bytes);
  await call(uid, "media.authorize", {
    uploadId,
    sha256,
    bytes: bytes.length,
    kind,
    ...(kind === "AVATAR" ? {} : { coupleId }),
  });
  await mediaBucket()
    .file("pending/" + uid + "/" + uploadId)
    .save(bytes, {
      resumable: false,
      metadata: { contentType: "image/png", metadata: { sha256 } },
    });
  return { uploadId, sha256 };
}
beforeEach(async () => {
  for (const collection of await db.listCollections())
    await db.recursiveDelete(collection);
  await mediaBucket().deleteFiles({ force: true });
});
test("drawing finalization stores one item and durable event; retries need no source re-upload", async () => {
  const coupleId = await pair();
  const source = await upload("alice", coupleId);
  const key = randomUUID();
  const data = { coupleId, kind: "DRAWING", caption: "", ...source };
  const first = await call("alice", "content.send", data, key);
  assert.deepEqual(await call("alice", "content.send", data, key), first);
  assert.equal(
    (await db.collection("couples/" + coupleId + "/content").get()).size,
    1,
  );
  assert.equal((await db.collection("deliveryEvents").get()).size, 1);
  assert.equal(
    (
      await mediaBucket()
        .file("pending/alice/" + source.uploadId)
        .exists()
    )[0],
    false,
  );
  const result = await call("bob", "content.media", {
    coupleId,
    contentId: first.contentId,
  });
  const metadata = await sharp(Buffer.from(result.base64, "base64")).metadata();
  assert.equal(metadata.format, "png");
  assert.equal(
    (await call("alice", "operation.status", { requestId: key, coupleId }))
      .completed,
    true,
  );
});

test("an identity change cannot submit a queued item as the other member, and sender fields are rejected", async () => {
  const coupleId = await pair();
  await assert.rejects(
    execute("bob", {
      v: 1,
      actorUid: "alice",
      op: "content.send",
      requestId: randomUUID(),
      data: { coupleId, kind: "MESSAGE", caption: "Original author's draft" },
    }),
    { code: "unauthenticated" },
  );
  await assert.rejects(
    call("alice", "content.send", {
      coupleId,
      kind: "MESSAGE",
      caption: "A draft",
      senderUid: "bob",
    }),
    { code: "invalid-argument" },
  );
  assert.equal(
    (await db.collection("couples/" + coupleId + "/content").get()).size,
    0,
  );
});
test("photos lose metadata, and malformed images/checksums cannot be published", async () => {
  const coupleId = await pair();
  const image = await sharp({
    create: { width: 20, height: 30, channels: 3, background: "#abc" },
  })
    .jpeg()
    .withMetadata()
    .toBuffer();
  assert.ok((await sharp(image).metadata()).exif);
  const source = await upload("alice", coupleId, "PHOTO", image);
  const item = await call("alice", "content.send", {
    coupleId,
    kind: "PHOTO",
    ...source,
  });
  const result = await call("bob", "content.media", {
    coupleId,
    contentId: item.contentId,
  });
  assert.equal(
    (await sharp(Buffer.from(result.base64, "base64")).metadata()).exif,
    undefined,
  );
  const invalid = await upload(
    "alice",
    coupleId,
    "PHOTO",
    Buffer.from("not an image"),
  );
  await assert.rejects(
    call("alice", "content.send", { coupleId, kind: "PHOTO", ...invalid }),
    { code: "invalid-argument" },
  );
  const valid = await upload("alice", coupleId);
  await assert.rejects(
    call("alice", "content.send", {
      coupleId,
      kind: "DRAWING",
      ...valid,
      sha256: "0".repeat(64),
    }),
    { code: "invalid-argument" },
  );
});
test("both can save/react/delete; receipts distinguish received/opened and never trust the sender", async () => {
  const coupleId = await pair();
  const item = await call("alice", "content.send", {
    coupleId,
    kind: "MESSAGE",
    caption: "A private note",
  });
  await call("bob", "content.save", {
    coupleId,
    contentId: item.contentId,
    saved: true,
  });
  await call("bob", "content.react", {
    coupleId,
    contentId: item.contentId,
    reaction: "❤️",
  });
  await call("alice", "content.ack", {
    coupleId,
    ids: [item.contentId],
    opened: true,
  });
  const ref = db.doc("couples/" + coupleId + "/content/" + item.contentId);
  assert.equal((await ref.get()).data().openedAt, null);
  await call("bob", "content.ack", {
    coupleId,
    ids: [item.contentId],
    opened: true,
  });
  assert.ok((await ref.get()).data().openedAt);
  assert.equal((await ref.get()).data().reactions.bob, "❤️");
  await call("alice", "content.save", {
    coupleId,
    contentId: item.contentId,
    saved: false,
  });
  await call("bob", "content.delete", { coupleId, contentId: item.contentId });
  assert.equal((await ref.get()).data().deleted, true);
  await assert.rejects(
    call("alice", "content.save", {
      coupleId,
      contentId: item.contentId,
      saved: true,
    }),
    { code: "not-found" },
  );
  await cleanup();
  assert.equal((await ref.get()).exists, false);
});
test("another couple cannot read, save, acknowledge or delete private media; unpair denies old IDs and clears bytes", async () => {
  const coupleId = await pair();
  await pair("charlie", "dana");
  const source = await upload("alice", coupleId);
  const item = await call("alice", "content.send", {
    coupleId,
    kind: "DRAWING",
    ...source,
  });
  for (const [op, extra] of [
    ["content.media", {}],
    ["content.save", { saved: true }],
    ["content.delete", {}],
  ]) {
    await assert.rejects(
      call("charlie", op, { coupleId, contentId: item.contentId, ...extra }),
      { code: "permission-denied" },
    );
  }
  await call("bob", "pair.end", { coupleId, confirm: true });
  await assert.rejects(
    call("alice", "content.media", { coupleId, contentId: item.contentId }),
    { code: "permission-denied" },
  );
  await cleanup();
  assert.equal(
    (
      await mediaBucket().getFiles({
        prefix: "private/couples/" + coupleId + "/",
      })
    )[0].length,
    0,
  );
});
test("avatars remain owner-private until paired and upload permits are bounded", async () => {
  const image = await upload("alice", null, "AVATAR");
  await call("alice", "profile.avatar", image);
  await call("alice", "profile.avatarMedia", { ownerUid: "alice" });
  await assert.rejects(
    call("bob", "profile.avatarMedia", { ownerUid: "alice" }),
    { code: "permission-denied" },
  );
  await pair();
  await call("bob", "profile.avatarMedia", { ownerUid: "alice" });
  for (let i = 0; i < 20; i++)
    await call("alice", "media.authorize", {
      uploadId: randomUUID(),
      sha256: "a".repeat(64),
      bytes: 10,
      kind: "AVATAR",
    });
  await assert.rejects(
    call("alice", "media.authorize", {
      uploadId: randomUUID(),
      sha256: "b".repeat(64),
      bytes: 10,
      kind: "AVATAR",
    }),
    { code: "resource-exhausted" },
  );
});

test("orphan cleanup does not expire retained exchange history or its media", async () => {
  const coupleId = await pair();
  const source = await upload("alice", coupleId);
  const item = await call("alice", "content.send", {
    coupleId,
    kind: "DRAWING",
    ...source,
  });
  const orphan = "private/couples/" + coupleId + "/content/orphan";
  await mediaBucket()
    .file(orphan)
    .save(Buffer.from("unpublished"), { resumable: false });
  await sweepOrphans(Date.now() + 49 * 3_600_000);
  assert.equal((await mediaBucket().file(orphan).exists())[0], false);
  assert.ok(
    (
      await call("bob", "content.media", {
        coupleId,
        contentId: item.contentId,
      })
    ).base64,
  );
});
