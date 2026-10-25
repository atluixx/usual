import { getAuth } from "firebase-admin/auth";
import { Timestamp } from "firebase-admin/firestore";
import { clearState, db, now, state } from "./core.js";
import { mediaBucket, sweepOrphans } from "./media.js";
import { expireUploads, revokeCoupleUploads } from "./uploads.js";
import { expireGameInvitations } from "./games.js";

async function deletePrefix(prefix: string): Promise<void> {
  for (let page = 0; page < 5; page++) {
    const [files] = await mediaBucket().getFiles({
      prefix,
      maxResults: 100,
      autoPaginate: false,
    });
    if (!files.length) return;
    await Promise.all(
      files.map((file) => file.delete({ ignoreNotFound: true })),
    );
  }
  if (
    (
      await mediaBucket().getFiles({
        prefix,
        maxResults: 1,
        autoPaginate: false,
      })
    )[0].length
  )
    throw new Error("More media cleanup batches are needed.");
}

async function redactInvitations(field: string, value: string): Promise<void> {
  for (let page = 0; page < 5; page++) {
    const entries = await db
      .collection("invitations")
      .where(field, "==", value)
      .limit(100)
      .get();
    if (entries.empty) return;
    const batch = db.batch();
    for (const entry of entries.docs)
      batch.set(entry.ref, {
        status: "spent",
        expiresAt: entry.data().expiresAt,
        createdAt: entry.data().createdAt,
      });
    await batch.commit();
  }
  if (
    !(
      await db
        .collection("invitations")
        .where(field, "==", value)
        .limit(1)
        .get()
    ).empty
  )
    throw new Error("Another cleanup batch is needed.");
}

export async function cleanup(): Promise<void> {
  await expireGameInvitations();
  const invitations = await db
    .collection("invitations")
    .where("status", "in", ["pending", "claimed"])
    .where("expiresAt", "<=", now())
    .orderBy("expiresAt")
    .limit(30)
    .get();
  for (const invitation of invitations.docs)
    await db.runTransaction(async (tx) => {
      const data = (await tx.get(invitation.ref)).data();
      if (
        !data ||
        !["pending", "claimed"].includes(data.status) ||
        data.expiresAt.toMillis() > Date.now()
      )
        return;
      const members = [data.creatorUid, data.claimantUid].filter(
        (value): value is string => typeof value === "string",
      );
      const memberships = await Promise.all(
        members.map((uid) => state(tx, uid)),
      );
      memberships.forEach((entry, index) => {
        if (entry.invitationId === invitation.id)
          clearState(tx, members[index]);
      });
      tx.update(invitation.ref, {
        status: "expired",
        creatorName: "Partner",
        claimantName: null,
        claimantUid: null,
        updatedAt: now(),
      });
    });
  const jobs = await db
    .collection("cleanupJobs")
    .where("nextAttemptAt", "<=", now())
    .orderBy("nextAttemptAt")
    .limit(20)
    .get();
  for (const job of jobs.docs) {
    const data = job.data();
    try {
      if (data.kind === "couple") {
        await revokeCoupleUploads(data.target);
        if (
          !process.env.GCLOUD_PROJECT?.startsWith("demo-") ||
          process.env.FIREBASE_STORAGE_EMULATOR_HOST
        )
          await deletePrefix("private/couples/" + data.target + "/");
        await redactInvitations("coupleId", data.target);
        await db.recursiveDelete(db.doc("couples/" + data.target));
      } else if (data.kind === "account") {
        try {
          await getAuth().deleteUser(data.target);
        } catch (cause) {
          if ((cause as { code?: string }).code !== "auth/user-not-found")
            throw cause;
        }
        await redactInvitations("creatorUid", data.target);
        await redactInvitations("claimantUid", data.target);
        if (
          !process.env.GCLOUD_PROJECT?.startsWith("demo-") ||
          process.env.FIREBASE_STORAGE_EMULATOR_HOST
        ) {
          await deletePrefix("private/users/" + data.target + "/");
          await deletePrefix("pending/" + data.target + "/");
        }
        for (const collection of [
          "users",
          "pairing",
          "preferences",
          "operations",
          "limits",
          "uploads",
          "avatars",
        ])
          await db.recursiveDelete(db.doc(collection + "/" + data.target));
        await db.doc("accounts/" + data.target).set({
          state: "deleted",
          updatedAt: now(),
          expiresAt: Timestamp.fromMillis(Date.now() + 30 * 86_400_000),
        });
      } else if (data.kind === "content" || data.kind === "object") {
        if (data.path)
          await mediaBucket().file(data.path).delete({ ignoreNotFound: true });
        if (data.resourcePath) await db.doc(data.resourcePath).delete();
      } else throw new Error("Unknown cleanup operation.");
      await job.ref.delete();
    } catch {
      const attempts = (data.attempts || 0) + 1;
      await job.ref.update({
        attempts,
        nextAttemptAt: Timestamp.fromMillis(
          Date.now() + Math.min(3_600_000, 5000 * 2 ** Math.min(attempts, 10)),
        ),
      });
    }
  }
  const expired = await db
    .collection("accounts")
    .where("expiresAt", "<=", now())
    .limit(30)
    .get();
  await Promise.all(expired.docs.map((entry) => entry.ref.delete()));
  await expireUploads();
  if (
    !process.env.GCLOUD_PROJECT?.startsWith("demo-") ||
    process.env.FIREBASE_STORAGE_EMULATOR_HOST
  )
    await sweepOrphans();
}
