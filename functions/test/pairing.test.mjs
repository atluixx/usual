import assert from "node:assert/strict";
import { beforeEach, test } from "node:test";
import { randomUUID, randomBytes } from "node:crypto";
import { getAuth } from "firebase-admin/auth";
import { Timestamp } from "firebase-admin/firestore";
import { execute } from "../lib/index.js";
import { db } from "../lib/core.js";
import { cleanup } from "../lib/maintenance.js";

assert.equal(process.env.GCLOUD_PROJECT, "demo-usual");
for (const key of ["FIRESTORE_EMULATOR_HOST", "FIREBASE_AUTH_EMULATOR_HOST"]) {
  assert.match(process.env[key] || "", /^(127\.0\.0\.1|localhost):\d+$/);
}
const code = () => randomBytes(12).toString("hex").toUpperCase();
const call = (uid, op, data, requestId = randomUUID()) =>
  execute(uid, { v: 1, op, requestId, data });
const rejected = (promise, expected) =>
  assert.rejects(promise, { code: expected });
async function invite(uid = "alice") {
  const value = code();
  const result = await call(uid, "pair.create", { code: value });
  return { ...result, code: value };
}
async function pair(a = "alice", b = "bob") {
  const invitation = await invite(a);
  await call(b, "pair.claim", { code: invitation.code });
  return call(a, "pair.confirm", {
    invitationId: invitation.invitationId,
    confirm: true,
  });
}
beforeEach(async () => {
  for (const entry of await db.listCollections())
    await db.recursiveDelete(entry);
});

test("claim grants no access; explicit creator confirmation makes exactly two memberships", async () => {
  const invitation = await invite();
  await call("bob", "pair.claim", { code: invitation.code });
  assert.equal((await db.doc("pairing/bob").get()).data().coupleId, null);
  assert.equal((await db.collection("couples").get()).size, 0);
  await rejected(
    call("bob", "pair.confirm", {
      invitationId: invitation.invitationId,
      confirm: true,
    }),
    "permission-denied",
  );
  await rejected(
    call("alice", "pair.confirm", { invitationId: invitation.invitationId }),
    "failed-precondition",
  );
  const result = await call("alice", "pair.confirm", {
    invitationId: invitation.invitationId,
    confirm: true,
  });
  const couple = (await db.doc("couples/" + result.coupleId).get()).data();
  assert.deepEqual(couple.memberUids, ["alice", "bob"]);
  for (const member of couple.memberUids)
    assert.equal(
      (await db.doc("pairing/" + member).get()).data().coupleId,
      result.coupleId,
    );
  assert.equal(
    (await db.doc("invitations/" + invitation.invitationId).get()).data().code,
    undefined,
  );
});

test("retries are idempotent and changed payloads cannot reuse a request ID", async () => {
  const key = randomUUID();
  const value = code();
  const first = await call("alice", "pair.create", { code: value }, key);
  assert.deepEqual(
    await call("alice", "pair.create", { code: value }, key),
    first,
  );
  await rejected(
    call("alice", "pair.create", { code: code() }, key),
    "already-exists",
  );
  const claimKey = randomUUID();
  await call("bob", "pair.claim", { code: value }, claimKey);
  await call("bob", "pair.claim", { code: value }, claimKey);
  const confirmKey = randomUUID();
  const result = await call(
    "alice",
    "pair.confirm",
    { invitationId: first.invitationId, confirm: true },
    confirmKey,
  );
  assert.deepEqual(
    await call(
      "alice",
      "pair.confirm",
      { invitationId: first.invitationId, confirm: true },
      confirmKey,
    ),
    result,
  );
  assert.equal((await db.collection("couples").get()).size, 1);
});

test("expiry, revocation, self-pairing, code reuse and already paired users are rejected", async () => {
  const invitation = await invite();
  await rejected(
    call("alice", "pair.claim", { code: invitation.code }),
    "failed-precondition",
  );
  await db
    .doc("invitations/" + invitation.invitationId)
    .update({ expiresAt: Timestamp.fromMillis(Date.now() - 1) });
  await rejected(
    call("bob", "pair.claim", { code: invitation.code }),
    "failed-precondition",
  );
  await call("alice", "pair.cancel", { invitationId: invitation.invitationId });
  await rejected(
    call("bob", "pair.claim", { code: invitation.code }),
    "failed-precondition",
  );
  const second = await invite();
  await call("bob", "pair.claim", { code: second.code });
  await call("alice", "pair.confirm", {
    invitationId: second.invitationId,
    confirm: true,
  });
  await rejected(
    call("charlie", "pair.claim", { code: second.code }),
    "failed-precondition",
  );
  await rejected(
    call("alice", "pair.create", { code: code() }),
    "failed-precondition",
  );
});

test("concurrent claimants and confirmations cannot create a third member", async () => {
  const invitation = await invite();
  const claims = await Promise.allSettled(
    ["bob", "charlie"].map((uid) =>
      call(uid, "pair.claim", { code: invitation.code }),
    ),
  );
  assert.equal(
    claims.filter((entry) => entry.status === "fulfilled").length,
    1,
  );
  const confirmations = await Promise.allSettled(
    [1, 2].map(() =>
      call("alice", "pair.confirm", {
        invitationId: invitation.invitationId,
        confirm: true,
      }),
    ),
  );
  assert.equal(
    confirmations.filter((entry) => entry.status === "fulfilled").length,
    1,
  );
  const couples = await db.collection("couples").get();
  assert.equal(couples.size, 1);
  assert.equal(new Set(couples.docs[0].data().memberUids).size, 2);
});

test("one claimant cannot join concurrent invitations", async () => {
  const a = await invite("alice");
  const b = await invite("charlie");
  const result = await Promise.allSettled(
    [a, b].map((entry) => call("bob", "pair.claim", { code: entry.code })),
  );
  assert.equal(
    result.filter((entry) => entry.status === "fulfilled").length,
    1,
  );
});

test("maintenance expires an invitation and releases both pending participants", async () => {
  const invitation = await invite();
  await call("bob", "pair.claim", { code: invitation.code });
  await db
    .doc("invitations/" + invitation.invitationId)
    .update({ expiresAt: Timestamp.fromMillis(Date.now() - 1) });
  await cleanup();
  for (const uid of ["alice", "bob"])
    assert.equal(
      (await db.doc("pairing/" + uid).get()).data().invitationId,
      null,
    );
  assert.equal(
    (await db.doc("invitations/" + invitation.invitationId).get()).data()
      .status,
    "expired",
  );
  await rejected(
    call("alice", "pair.create", { code: invitation.code }),
    "already-exists",
  );
});

test("unpair revokes both memberships before durable physical cleanup", async () => {
  const { coupleId } = await pair();
  await rejected(
    call("stranger", "pair.end", { coupleId, confirm: true }),
    "permission-denied",
  );
  const key = randomUUID();
  await call("bob", "pair.end", { coupleId, confirm: true }, key);
  assert.equal(
    (await db.doc("couples/" + coupleId).get()).data().status,
    "closed",
  );
  for (const uid of ["alice", "bob"])
    assert.equal((await db.doc("pairing/" + uid).get()).data().coupleId, null);
  assert.equal(
    (await db.doc("cleanupJobs/couple_" + coupleId).get()).exists,
    true,
  );
  await call("bob", "pair.end", { coupleId, confirm: true }, key);
  await cleanup();
  assert.equal((await db.doc("couples/" + coupleId).get()).exists, false);
});

test("account deletion revokes a couple, blocks stale tokens and durably removes only that identity", async () => {
  const uid = "delete-" + randomUUID();
  await getAuth().createUser({ uid });
  const { coupleId } = await pair(uid, "bob");
  await call(uid, "identity.delete", { confirm: true });
  assert.equal(
    (await db.doc("accounts/" + uid).get()).data().state,
    "deleting",
  );
  assert.equal((await db.doc("pairing/bob").get()).data().coupleId, null);
  await rejected(
    call(uid, "pair.create", { code: code() }),
    "permission-denied",
  );
  await cleanup();
  assert.equal((await db.doc("accounts/" + uid).get()).data().state, "deleted");
  assert.equal((await db.doc("couples/" + coupleId).get()).exists, false);
  await rejected(getAuth().getUser(uid), "auth/user-not-found");
});
