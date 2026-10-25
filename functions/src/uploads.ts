import { Timestamp, Transaction } from "firebase-admin/firestore";
import {
  activeAccount,
  activeCouple,
  Args,
  db,
  fail,
  fields,
  id,
  integer,
  now,
  Result,
  text,
} from "./core.js";
import { MAX_MEDIA_BYTES, sourceHash } from "./media.js";

export const permitRef = (uid: string, uploadId: string) =>
  db.doc("uploads/" + uid + "/items/" + uploadId);
export async function authorizeUpload(
  uid: string,
  data: Args,
): Promise<Result> {
  fields(data, ["uploadId", "sha256", "bytes", "kind", "coupleId"]);
  const uploadId = id(data, "uploadId");
  const sha256 = sourceHash(data);
  const bytes = integer(data, "bytes", 1, MAX_MEDIA_BYTES);
  const kind = text(data, "kind", 1, 8);
  if (!["PHOTO", "DRAWING", "AVATAR"].includes(kind))
    return fail("invalid-argument", "Invalid upload kind.");
  const coupleId = kind === "AVATAR" ? null : id(data, "coupleId");
  if (kind === "AVATAR" && data.coupleId != null)
    return fail("invalid-argument", "An avatar belongs to its owner.");
  return db.runTransaction(async (tx) => {
    await activeAccount(tx, uid);
    if (coupleId) await activeCouple(tx, uid, coupleId);
    const reference = permitRef(uid, uploadId);
    const old = (await tx.get(reference)).data();
    const counterRef = db.doc("uploads/" + uid);
    const counter = (await tx.get(counterRef)).data()?.pendingCount || 0;
    if (
      old &&
      (old.sha256 !== sha256 ||
        old.bytes !== bytes ||
        old.kind !== kind ||
        old.coupleId !== coupleId)
    )
      return fail(
        "already-exists",
        "This upload ID already belongs to another image.",
      );
    if (old?.state === "consumed")
      return fail(
        "failed-precondition",
        "This upload is already finalized. Refresh its result.",
      );
    const count = old?.state === "pending" ? counter : counter + 1;
    if (count > 20)
      return fail(
        "resource-exhausted",
        "Finish or discard pending uploads before adding more.",
      );
    const expiresAt = Timestamp.fromMillis(Date.now() + 15 * 60_000);
    tx.set(reference, {
      uid,
      uploadId,
      sha256,
      bytes,
      kind,
      coupleId,
      state: "pending",
      expiresAt,
      createdAt: old?.createdAt || now(),
    });
    tx.set(counterRef, { pendingCount: count });
    return { uploadId, expiresAt: expiresAt.toMillis() };
  });
}
export async function consumeUpload(
  tx: Transaction,
  uid: string,
  uploadId: string,
  sha256: string,
  kind: string,
  coupleId: string | null,
): Promise<() => void> {
  const reference = permitRef(uid, uploadId);
  const permit = (await tx.get(reference)).data();
  const counterRef = db.doc("uploads/" + uid);
  const counter = (await tx.get(counterRef)).data()?.pendingCount || 0;
  if (
    !permit ||
    permit.state !== "pending" ||
    permit.sha256 !== sha256 ||
    permit.kind !== kind ||
    permit.coupleId !== coupleId ||
    permit.expiresAt.toMillis() <= Date.now()
  )
    return fail(
      "failed-precondition",
      "This upload authorization expired. Retry the upload.",
    );
  return () => {
    tx.update(reference, {
      state: "consumed",
      expiresAt: Timestamp.fromMillis(Date.now() + 48 * 3_600_000),
    });
    tx.set(counterRef, { pendingCount: Math.max(0, counter - 1) });
  };
}
export async function expireUploads(): Promise<void> {
  const entries = await db
    .collectionGroup("items")
    .where("expiresAt", "<=", now())
    .limit(50)
    .get();
  for (const entry of entries.docs)
    await db.runTransaction(async (tx) => {
      const permit = (await tx.get(entry.ref)).data();
      if (!permit || permit.expiresAt.toMillis() > Date.now()) return;
      const counterRef = db.doc("uploads/" + permit.uid);
      const counter = (await tx.get(counterRef)).data();
      if (counter && permit.state === "pending")
        tx.update(counterRef, {
          pendingCount: Math.max(0, (counter.pendingCount || 0) - 1),
        });
      tx.delete(entry.ref);
    });
}

export async function revokeCoupleUploads(coupleId: string): Promise<void> {
  for (let page = 0; page < 5; page++) {
    const entries = await db
      .collectionGroup("items")
      .where("coupleId", "==", coupleId)
      .limit(100)
      .get();
    if (entries.empty) return;
    for (const entry of entries.docs) {
      const value = entry.data();
      await db.runTransaction(async (tx) => {
        const permit = (await tx.get(entry.ref)).data();
        const counterRef = db.doc("uploads/" + value.uid);
        const counter = (await tx.get(counterRef)).data();
        if (permit?.state === "pending" && counter)
          tx.update(counterRef, {
            pendingCount: Math.max(0, (counter.pendingCount || 0) - 1),
          });
        tx.delete(entry.ref);
        tx.set(
          db.doc("cleanupJobs/pending_" + value.uid + "_" + value.uploadId),
          {
            kind: "object",
            path: "pending/" + value.uid + "/" + value.uploadId,
            attempts: 0,
            nextAttemptAt: now(),
          },
        );
      });
    }
  }
  if (
    !(
      await db
        .collectionGroup("items")
        .where("coupleId", "==", coupleId)
        .limit(1)
        .get()
    ).empty
  )
    throw new Error("More upload cleanup batches are needed.");
}

export async function discardUpload(uid: string, data: Args): Promise<Result> {
  fields(data, ["uploadId"]);
  const uploadId = id(data, "uploadId");
  await db.runTransaction(async (tx) => {
    await activeAccount(tx, uid);
    const reference = permitRef(uid, uploadId);
    const permit = (await tx.get(reference)).data();
    const counterRef = db.doc("uploads/" + uid);
    const counter = (await tx.get(counterRef)).data();
    if (permit)
      tx.update(reference, {
        state: "revoked",
        expiresAt: Timestamp.fromMillis(Date.now() + 48 * 3_600_000),
      });
    if (permit?.state === "pending" && counter)
      tx.update(counterRef, {
        pendingCount: Math.max(0, (counter.pendingCount || 0) - 1),
      });
    tx.set(db.doc("cleanupJobs/pending_" + uid + "_" + uploadId), {
      kind: "object",
      path: "pending/" + uid + "/" + uploadId,
      attempts: 0,
      nextAttemptAt: now(),
    });
  });
  return { discarded: true };
}
