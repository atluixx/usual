import { randomUUID } from "node:crypto";
import { getAuth } from "firebase-admin/auth";
import { Timestamp, Transaction } from "firebase-admin/firestore";
import {
  activeAccount,
  activeCouple,
  Args,
  clearState,
  confirmed,
  db,
  fail,
  fields,
  hash,
  id,
  now,
  once,
  Result,
  state,
  stateRef,
  text,
  unpaired,
} from "./core.js";

function codeHash(data: Args): string {
  const code = text(data, "code", 16, 40).replace(/[-\s]/g, "").toUpperCase();
  if (!/^[A-F0-9]{24}$/.test(code))
    return fail(
      "invalid-argument",
      "Use the complete 24-character pairing code.",
    );
  return hash(code);
}
const invitationRef = (invitationId: string) =>
  db.doc("invitations/" + invitationId);
async function name(tx: Transaction, uid: string): Promise<string> {
  const value = (await tx.get(db.doc("users/" + uid))).data()?.displayName;
  return typeof value === "string" &&
    value.trim().length > 0 &&
    value.length <= 80
    ? value
    : "Partner";
}
function validInvitation(value: Args | undefined): asserts value is Args {
  if (
    !value ||
    !(value.expiresAt instanceof Timestamp) ||
    value.expiresAt.toMillis() <= Date.now() ||
    !["pending", "claimed"].includes(value.status as string)
  )
    fail(
      "failed-precondition",
      "This invitation is expired, revoked or already used.",
    );
}
export async function pairing(
  uid: string,
  op: string,
  key: string,
  data: Args,
): Promise<Result> {
  if (op === "pair.create") {
    fields(data, ["code"]);
    const invitationId = codeHash(data);
    return once(
      uid,
      op,
      key,
      data,
      async (tx) => {
        const membership = await state(tx, uid);
        unpaired(membership);
        if (membership.invitationId)
          fail("failed-precondition", "Cancel the previous invitation first.");
        const displayName = await name(tx, uid);
        if ((await tx.get(invitationRef(invitationId))).exists)
          fail("already-exists", "Create a fresh pairing code.");
        const expiresAt = Timestamp.fromMillis(Date.now() + 20 * 60_000);
        tx.create(invitationRef(invitationId), {
          creatorUid: uid,
          creatorName: displayName,
          claimantUid: null,
          claimantName: null,
          status: "pending",
          coupleId: null,
          createdAt: now(),
          updatedAt: now(),
          expiresAt,
        });
        tx.set(stateRef(uid), {
          coupleId: null,
          invitationId,
          status: "inviting",
          updatedAt: now(),
        });
        return { invitationId, expiresAt: expiresAt.toMillis() };
      },
      async (tx) => {
        const invitation = (await tx.get(invitationRef(invitationId))).data();
        if (invitation?.creatorUid !== uid)
          return fail("permission-denied", "Invitation unavailable.");
        if (invitation.status === "accepted")
          await activeCouple(tx, uid, invitation.coupleId);
        else validInvitation(invitation);
      },
    );
  }
  if (op === "pair.claim") {
    fields(data, ["code"]);
    const invitationId = codeHash(data);
    return once(
      uid,
      op,
      key,
      data,
      async (tx) => {
        const invitation = (await tx.get(invitationRef(invitationId))).data();
        validInvitation(invitation);
        if (invitation.status !== "pending" || invitation.creatorUid === uid)
          fail("failed-precondition", "This invitation cannot be claimed.");
        const creatorUid = invitation.creatorUid as string;
        const [own, creator, displayName] = await Promise.all([
          state(tx, uid),
          state(tx, creatorUid),
          name(tx, uid),
        ]);
        await activeAccount(tx, creatorUid);
        unpaired(own);
        unpaired(creator);
        if (own.invitationId || creator.invitationId !== invitationId)
          fail(
            "failed-precondition",
            "Finish or cancel the current invitation.",
          );
        tx.update(invitationRef(invitationId), {
          claimantUid: uid,
          claimantName: displayName,
          status: "claimed",
          updatedAt: now(),
        });
        tx.set(stateRef(uid), {
          coupleId: null,
          invitationId,
          status: "requested",
          updatedAt: now(),
        });
        return { invitationId };
      },
      async (tx) => {
        const invitation = (await tx.get(invitationRef(invitationId))).data();
        if (invitation?.claimantUid !== uid)
          return fail("permission-denied", "Invitation unavailable.");
        if (invitation.status === "accepted")
          await activeCouple(tx, uid, invitation.coupleId);
        else validInvitation(invitation);
      },
    );
  }
  if (op === "pair.confirm") {
    fields(data, ["invitationId", "confirm"]);
    confirmed(data);
    const invitationId = id(data, "invitationId");
    const coupleId = randomUUID();
    return once(
      uid,
      op,
      key,
      data,
      async (tx) => {
        const invitation = (await tx.get(invitationRef(invitationId))).data();
        validInvitation(invitation);
        if (invitation.creatorUid !== uid || invitation.status !== "claimed")
          fail(
            "permission-denied",
            "Only the creator can confirm the requested pairing.",
          );
        const claimant = invitation.claimantUid as string;
        const [own, other] = await Promise.all([
          state(tx, uid),
          state(tx, claimant),
        ]);
        await activeAccount(tx, claimant);
        unpaired(own);
        unpaired(other);
        if (
          claimant === uid ||
          own.invitationId !== invitationId ||
          other.invitationId !== invitationId
        )
          fail(
            "failed-precondition",
            "Pairing state changed. Create a fresh invitation.",
          );
        tx.create(db.doc("couples/" + coupleId), {
          memberUids: [uid, claimant],
          memberNames: {
            [uid]: invitation.creatorName,
            [claimant]: invitation.claimantName,
          },
          status: "active",
          createdAt: now(),
          updatedAt: now(),
        });
        for (const member of [uid, claimant])
          tx.set(stateRef(member), {
            coupleId,
            invitationId: null,
            status: "paired",
            updatedAt: now(),
          });
        tx.update(invitationRef(invitationId), {
          status: "accepted",
          coupleId,
          updatedAt: now(),
        });
        return { coupleId };
      },
      async (tx, result) => {
        await activeCouple(tx, uid, result.coupleId as string);
      },
    );
  }
  if (op === "pair.cancel") {
    fields(data, ["invitationId"]);
    const invitationId = id(data, "invitationId");
    return once(uid, op, key, data, async (tx) => {
      const invitation = (await tx.get(invitationRef(invitationId))).data();
      if (
        !invitation ||
        ![invitation.creatorUid, invitation.claimantUid].includes(uid)
      )
        return fail("permission-denied", "Invitation unavailable.");
      if (invitation.status === "accepted")
        fail("failed-precondition", "This invitation already formed a couple.");
      const participants = [
        invitation.creatorUid,
        invitation.claimantUid,
      ].filter((value): value is string => typeof value === "string");
      const memberships = await Promise.all(
        participants.map((member) => state(tx, member)),
      );
      memberships.forEach((membership, index) => {
        if (membership.invitationId === invitationId)
          clearState(tx, participants[index]);
      });
      tx.update(invitationRef(invitationId), {
        status: "revoked",
        updatedAt: now(),
      });
      return { cancelled: true };
    });
  }
  if (op === "pair.end") {
    fields(data, ["coupleId", "confirm"]);
    confirmed(data);
    const coupleId = id(data, "coupleId");
    return once(uid, op, key, data, async (tx) => {
      const couple = await activeCouple(tx, uid, coupleId);
      closeCouple(tx, coupleId, couple.memberUids as string[]);
      return { unpaired: true };
    });
  }
  return fail("invalid-argument", "Unknown pairing operation.");
}
export function closeCouple(
  tx: Transaction,
  coupleId: string,
  members: string[],
): void {
  tx.update(db.doc("couples/" + coupleId), {
    status: "closed",
    updatedAt: now(),
  });
  for (const member of members) clearState(tx, member);
  tx.set(db.doc("cleanupJobs/couple_" + coupleId), {
    kind: "couple",
    target: coupleId,
    state: "pending",
    attempts: 0,
    createdAt: now(),
    nextAttemptAt: now(),
  });
}
export async function deleteIdentity(uid: string): Promise<void> {
  await db.runTransaction(async (tx) => {
    if ((await tx.get(db.doc("accounts/" + uid))).data()?.state === "deleted")
      return;
    const membership = await state(tx, uid);
    const couple = membership.coupleId
      ? (await tx.get(db.doc("couples/" + membership.coupleId))).data()
      : null;
    const invitation = membership.invitationId
      ? (await tx.get(invitationRef(membership.invitationId as string))).data()
      : null;
    const participants = invitation
      ? [invitation.creatorUid, invitation.claimantUid].filter(
          (value): value is string => typeof value === "string",
        )
      : [];
    const memberships = await Promise.all(
      participants.map((member) => state(tx, member)),
    );
    if (couple?.status === "active")
      closeCouple(tx, membership.coupleId as string, couple.memberUids);
    if (invitation && invitation.status !== "accepted") {
      tx.update(invitationRef(membership.invitationId as string), {
        status: "revoked",
        updatedAt: now(),
      });
      memberships.forEach((entry, index) => {
        if (entry.invitationId === membership.invitationId)
          clearState(tx, participants[index]);
      });
    }
    clearState(tx, uid);
    tx.set(db.doc("accounts/" + uid), { state: "deleting", updatedAt: now() });
    tx.set(db.doc("cleanupJobs/account_" + uid), {
      kind: "account",
      target: uid,
      state: "pending",
      attempts: 0,
      createdAt: now(),
      nextAttemptAt: now(),
    });
  });
  try {
    await getAuth().revokeRefreshTokens(uid);
    await getAuth().deleteUser(uid);
  } catch {
    /* Durable cleanup retries. Access is already revoked by the tombstone. */
  }
}
