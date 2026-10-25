import { randomUUID } from "node:crypto";
import { FieldValue, Timestamp, Transaction } from "firebase-admin/firestore";
import {
  BatchResponse,
  getMessaging,
  FidMulticastMessage,
} from "firebase-admin/messaging";
import {
  activeAccount,
  activeCouple,
  Args,
  db,
  fail,
  fields,
  hash,
  id,
  now,
  once,
  Result,
  state,
  text,
} from "./core.js";
import { visibleContent } from "./content.js";
import { mediaResponse } from "./media.js";

const defaults = {
  contentNotifications: false,
  gameNotifications: false,
  widgetPreview: false,
};
async function preferences(tx: Transaction, uid: string) {
  const data = (await tx.get(db.doc("preferences/" + uid))).data();
  return {
    contentNotifications: data?.contentNotifications === true,
    gameNotifications: data?.gameNotifications === true,
    widgetPreview: data?.widgetPreview === true,
  };
}
export async function notifications(
  uid: string,
  op: string,
  key: string,
  data: Args,
): Promise<Result> {
  if (op === "preferences.set") {
    fields(data, Object.keys(defaults));
    if (Object.keys(defaults).some((field) => typeof data[field] !== "boolean"))
      return fail("invalid-argument", "Choose each privacy preference.");
    return once(
      uid,
      op,
      key,
      data,
      async (tx) => {
        await activeAccount(tx, uid);
        tx.set(db.doc("preferences/" + uid), { ...data, updatedAt: now() });
        return { updated: true };
      },
      async (tx) => {
        await activeAccount(tx, uid);
      },
    );
  }
  if (op === "device.register" || op === "device.unregister") {
    fields(data, ["fid"]);
    const fid = text(data, "fid", 22, 22);
    if (!/^[A-Za-z0-9_-]{22}$/.test(fid))
      return fail("invalid-argument", "Invalid device token.");
    const ref = db.doc("devices/" + hash(fid));
    return db.runTransaction(async (tx) => {
      await activeAccount(tx, uid);
      const existing = (await tx.get(ref)).data();
      if (existing && existing.uid !== uid)
        return fail(
          "permission-denied",
          "Refresh notifications for this identity.",
        );
      const entries = await tx.get(
        db.collection("devices").where("uid", "==", uid).limit(5),
      );
      if (op === "device.unregister") tx.delete(ref);
      else {
        if (!existing && entries.size >= 5)
          return fail(
            "resource-exhausted",
            "Notifications are already enabled on five devices.",
          );
        tx.set(ref, {
          uid,
          fid,
          updatedAt: now(),
          expiresAt: Timestamp.fromMillis(Date.now() + 30 * 86_400_000),
        });
      }
      return { updated: true };
    });
  }
  if (op === "notification.resolve") {
    fields(data, ["eventId"]);
    const eventId = id(data, "eventId");
    const destination = await db.runTransaction((tx) =>
      eligible(tx, eventId, uid),
    );
    return (
      destination || fail("not-found", "This update is no longer available.")
    );
  }
  if (op === "widget.snapshot") {
    fields(data, []);
    const candidate = await db.runTransaction(async (tx) => {
      await activeAccount(tx, uid);
      if (!(await preferences(tx, uid)).widgetPreview) return null;
      const membership = await state(tx, uid);
      if (typeof membership.coupleId !== "string") return null;
      const coupleId = membership.coupleId;
      await activeCouple(tx, uid, coupleId);
      const items = await tx.get(
        db
          .collection("couples/" + coupleId + "/content")
          .where("deleted", "==", false)
          .where("receiverUid", "==", uid)
          .where("kind", "in", ["PHOTO", "DRAWING"])
          .orderBy("createdAt", "desc")
          .limit(1),
      );
      const item = items.docs[0];
      return item
        ? {
            coupleId,
            contentId: item.id,
            path: item.data().mediaPath as string,
          }
        : null;
    });
    if (!candidate) return { visible: false };
    const image = await mediaResponse(candidate.path, true);
    await db.runTransaction(async (tx) => {
      const item = await visibleContent(
        tx,
        uid,
        candidate.coupleId,
        candidate.contentId,
      );
      if (
        !(await preferences(tx, uid)).widgetPreview ||
        item.receiverUid !== uid ||
        item.mediaPath !== candidate.path
      )
        return fail("permission-denied", "Widget preview is hidden.");
    });
    return {
      visible: true,
      coupleId: candidate.coupleId,
      contentId: candidate.contentId,
      ...image,
    };
  }
  return fail("invalid-argument", "Unknown notification operation.");
}
async function eligible(
  tx: Transaction,
  eventId: string,
  uid?: string,
): Promise<Result | null> {
  const entry = (await tx.get(db.doc("deliveryEvents/" + eventId))).data();
  if (
    !entry ||
    (uid && entry.recipientUid !== uid) ||
    entry.createdAt.toMillis() < Date.now() - 7 * 86_400_000
  )
    return null;
  const recipient = entry.recipientUid as string,
    coupleId = entry.coupleId as string;
  await activeCouple(tx, recipient, coupleId);
  const prefs = await preferences(tx, recipient);
  const content = entry.kind === "content";
  if (content ? !prefs.contentNotifications : !prefs.gameNotifications)
    return null;
  const item = (
    await tx.get(
      db.doc(
        "couples/" +
          coupleId +
          (content ? "/content/" : "/matches/") +
          entry.resourceId,
      ),
    )
  ).data();
  if (
    !item ||
    (content
      ? item.deleted || item.receiverUid !== recipient || item.openedAt
      : item.version !== entry.resourceVersion || item.phase === "EXPIRED")
  )
    return null;
  return {
    recipientUid: recipient,
    coupleId,
    resourceId: entry.resourceId,
    type: content ? "content" : "game",
    kind: entry.kind,
  };
}
type Sender = (message: FidMulticastMessage) => Promise<BatchResponse>;
export async function dispatch(
  eventId: string,
  sender?: Sender,
): Promise<void> {
  const ref = db.doc("deliveryEvents/" + eventId),
    lease = randomUUID();
  const claimed = await db.runTransaction(async (tx) => {
    const entry = (await tx.get(ref)).data();
    if (
      !entry ||
      !entry.nextAttemptAt ||
      entry.nextAttemptAt.toMillis() > Date.now()
    )
      return null;
    if (entry.attempts >= 8) {
      tx.update(ref, { state: "failed", nextAttemptAt: FieldValue.delete() });
      return null;
    }
    tx.update(ref, {
      lease,
      state: "sending",
      attempts: entry.attempts + 1,
      nextAttemptAt: Timestamp.fromMillis(Date.now() + 120_000),
    });
    return entry;
  });
  if (!claimed) return;
  let terminal: string | null = null;
  try {
    const destination = await db.runTransaction((tx) => eligible(tx, eventId));
    if (!destination) terminal = "suppressed";
    else {
      const devices = await db
        .collection("devices")
        .where("uid", "==", destination.recipientUid)
        .limit(5)
        .get();
      const current = devices.docs.filter(
        (doc) => doc.data().expiresAt.toMillis() > Date.now(),
      );
      if (!current.length) terminal = "no_device";
      else if (!sender && process.env.GCLOUD_PROJECT?.startsWith("demo-"))
        terminal = "emulated";
      else {
        const response = await (
          sender || ((message) => getMessaging().sendEachForMulticast(message))
        )({
          fids: current.map((doc) => doc.data().fid),
          data: { eventId, recipientUid: destination.recipientUid as string },
          android: { priority: "normal", ttl: 24 * 3_600_000 },
        });
        const invalid = new Set([
          "messaging/installation-id-not-registered",
          "messaging/registration-token-not-registered",
          "messaging/invalid-registration-token",
        ]);
        let retry = false;
        for (let index = 0; index < response.responses.length; index++) {
          const result = response.responses[index];
          if (!result.success && invalid.has(result.error?.code || ""))
            await current[index].ref.delete();
          else if (!result.success) retry = true;
        }
        if (!retry) terminal = "accepted";
      }
    }
  } catch (error) {
    if (
      ["permission-denied", "not-found", "unauthenticated"].includes(
        (error as { code?: string }).code || "",
      )
    )
      terminal = "suppressed";
  }
  await db.runTransaction(async (tx) => {
    const current = (await tx.get(ref)).data();
    if (!current || current.lease !== lease) return;
    const exhausted = current.attempts >= 8;
    tx.update(ref, {
      state: terminal || (exhausted ? "failed" : "pending"),
      lease: FieldValue.delete(),
      nextAttemptAt:
        terminal || exhausted
          ? FieldValue.delete()
          : Timestamp.fromMillis(
              Date.now() + Math.min(3_600_000, 15_000 * 2 ** current.attempts),
            ),
      updatedAt: now(),
    });
  });
}
export async function retryNotifications(): Promise<void> {
  const entries = await db
    .collection("deliveryEvents")
    .where("nextAttemptAt", "<=", now())
    .orderBy("nextAttemptAt")
    .limit(30)
    .get();
  for (const entry of entries.docs) await dispatch(entry.id);
  for (const collection of ["deliveryEvents", "devices"]) {
    const expired = await db
      .collection(collection)
      .where(
        collection === "devices" ? "expiresAt" : "createdAt",
        "<=",
        Timestamp.fromMillis(
          Date.now() - (collection === "devices" ? 0 : 7 * 86_400_000),
        ),
      )
      .limit(30)
      .get();
    await Promise.all(expired.docs.map((doc) => doc.ref.delete()));
  }
}
