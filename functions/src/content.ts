import { Transaction } from "firebase-admin/firestore";
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
  receipt,
  Result,
  state,
  text,
} from "./core.js";
import {
  mediaResponse,
  prepareMedia,
  removePending,
  sourceHash,
} from "./media.js";
import { consumeUpload } from "./uploads.js";

export const contentRef = (coupleId: string, contentId: string) =>
  db.doc("couples/" + coupleId + "/content/" + contentId);
export async function visibleContent(
  tx: Transaction,
  uid: string,
  coupleId: string,
  contentId: string,
): Promise<Args> {
  await activeCouple(tx, uid, coupleId);
  const item = (await tx.get(contentRef(coupleId, contentId))).data();
  if (!item || item.deleted)
    return fail("not-found", "This item is no longer available.");
  return item;
}
export function event(
  tx: Transaction,
  id: string,
  recipientUid: string,
  coupleId: string,
  kind: string,
  resourceId: string,
  resourceVersion: number | null = null,
): void {
  tx.create(db.doc("deliveryEvents/" + id), {
    recipientUid,
    coupleId,
    kind,
    resourceId,
    resourceVersion,
    state: "pending",
    attempts: 0,
    createdAt: now(),
    nextAttemptAt: now(),
  });
}
export async function content(
  uid: string,
  op: string,
  key: string,
  data: Args,
): Promise<Result> {
  const coupleId = id(data, "coupleId");
  if (op === "content.send") {
    fields(data, ["coupleId", "kind", "caption", "uploadId", "sha256"]);
    const kind = text(data, "kind", 1, 8);
    if (!["PHOTO", "DRAWING", "MESSAGE"].includes(kind))
      return fail("invalid-argument", "Unsupported content kind.");
    const caption =
      data.caption === undefined ? "" : text(data, "caption", 0, 280).trim();
    if (
      kind === "MESSAGE" &&
      (!caption || data.uploadId !== undefined || data.sha256 !== undefined)
    )
      return fail("invalid-argument", "Write a short message.");
    const contentId = hash(uid + ":" + key).slice(0, 32);
    const guard = async (tx: Transaction) => {
      await visibleContent(tx, uid, coupleId, contentId);
    };
    const previous = await receipt(uid, op, key, data, guard);
    if (previous) return previous;
    await db.runTransaction(async (tx) => {
      await activeCouple(tx, uid, coupleId);
    });
    const uploadId = kind === "MESSAGE" ? null : id(data, "uploadId");
    const media = uploadId
      ? await prepareMedia(
          uid,
          uploadId,
          sourceHash(data),
          "private/couples/" + coupleId + "/content/" + contentId,
          kind,
          coupleId,
        )
      : null;
    const result = await once(
      uid,
      op,
      key,
      data,
      async (tx) => {
        const couple = await activeCouple(tx, uid, coupleId);
        const consume = uploadId
          ? await consumeUpload(
              tx,
              uid,
              uploadId,
              sourceHash(data),
              kind,
              coupleId,
            )
          : () => {};
        const receiverUid = (couple.memberUids as string[]).find(
          (member) => member !== uid,
        )!;
        tx.create(contentRef(coupleId, contentId), {
          kind,
          caption,
          senderUid: uid,
          receiverUid,
          mediaPath: media?.path || null,
          mediaType: media?.mimeType || null,
          sha256: media?.sha256 || null,
          saved: false,
          savedAt: null,
          savedBy: null,
          deleted: false,
          receivedAt: null,
          openedAt: null,
          reactions: {},
          createdAt: now(),
          updatedAt: now(),
        });
        event(tx, contentId, receiverUid, coupleId, "content", contentId);
        consume();
        return { contentId, coupleId };
      },
      guard,
    );
    if (uploadId) await removePending(uid, uploadId);
    return result;
  }
  if (op === "content.media") {
    fields(data, ["coupleId", "contentId", "thumbnail"]);
    const contentId = id(data, "contentId");
    if (data.thumbnail !== undefined && typeof data.thumbnail !== "boolean")
      return fail("invalid-argument", "Invalid thumbnail option.");
    const item = await db.runTransaction((tx) =>
      visibleContent(tx, uid, coupleId, contentId),
    );
    if (typeof item.mediaPath !== "string")
      return fail("not-found", "This item has no image.");
    const result = await mediaResponse(item.mediaPath, data.thumbnail === true);
    await db.runTransaction(async (tx) => {
      await visibleContent(tx, uid, coupleId, contentId);
    });
    return result;
  }
  if (op === "content.ack") {
    fields(data, ["coupleId", "ids", "opened"]);
    const ids = data.ids;
    if (
      !Array.isArray(ids) ||
      ids.length < 1 ||
      ids.length > 20 ||
      !ids.every(
        (value) =>
          typeof value === "string" && /^[a-zA-Z0-9_-]{1,128}$/.test(value),
      ) ||
      typeof data.opened !== "boolean"
    )
      return fail("invalid-argument", "Invalid receipt.");
    return once(
      uid,
      op,
      key,
      data,
      async (tx) => {
        await activeCouple(tx, uid, coupleId);
        const items = await Promise.all(
          [...new Set(ids)].map((value) => tx.get(contentRef(coupleId, value))),
        );
        let acknowledged = 0;
        for (const item of items) {
          const value = item.data();
          if (!value || value.deleted || value.receiverUid !== uid) continue;
          tx.update(item.ref, {
            receivedAt: value.receivedAt || now(),
            openedAt: data.opened
              ? value.openedAt || now()
              : value.openedAt || null,
          });
          acknowledged++;
        }
        return { acknowledged, coupleId };
      },
      async (tx) => {
        await activeCouple(tx, uid, coupleId);
      },
    );
  }
  const contentId = id(data, "contentId");
  if (op === "content.save") {
    fields(data, ["coupleId", "contentId", "saved"]);
    if (typeof data.saved !== "boolean")
      return fail("invalid-argument", "Invalid saved state.");
  } else if (op === "content.react") {
    fields(data, ["coupleId", "contentId", "reaction"]);
    if (
      !["", "❤️", "😊", "✨", "😂", "🥰", "👍"].includes(
        text(data, "reaction", 0, 8),
      )
    )
      return fail("invalid-argument", "Unsupported reaction.");
  } else if (op === "content.delete") fields(data, ["coupleId", "contentId"]);
  else return fail("invalid-argument", "Unknown content operation.");
  return once(
    uid,
    op,
    key,
    data,
    async (tx) => {
      const item = await visibleContent(tx, uid, coupleId, contentId);
      if (op === "content.save")
        tx.update(contentRef(coupleId, contentId), {
          saved: data.saved,
          savedAt: data.saved ? now() : null,
          savedBy: data.saved ? uid : null,
          updatedAt: now(),
        });
      if (op === "content.react")
        tx.update(contentRef(coupleId, contentId), {
          reactions: {
            ...(item.reactions as Args),
            [uid]: data.reaction || null,
          },
          updatedAt: now(),
        });
      if (op === "content.delete") {
        tx.update(contentRef(coupleId, contentId), {
          deleted: true,
          updatedAt: now(),
        });
        tx.set(db.doc("cleanupJobs/content_" + coupleId + "_" + contentId), {
          kind: "content",
          path: item.mediaPath || null,
          resourcePath: contentRef(coupleId, contentId).path,
          attempts: 0,
          nextAttemptAt: now(),
        });
      }
      return { contentId, coupleId };
    },
    async (tx) => {
      if (op === "content.delete") {
        await activeCouple(tx, uid, coupleId);
        return;
      }
      await visibleContent(tx, uid, coupleId, contentId);
    },
  );
}

export async function avatar(
  uid: string,
  op: string,
  key: string,
  data: Args,
): Promise<Result> {
  if (op === "profile.avatar") {
    fields(data, ["uploadId", "sha256"]);
    const uploadId = id(data, "uploadId");
    const previous = await receipt(uid, op, key, data, async () => {});
    if (previous) return previous;
    await db.runTransaction((tx) => activeAccount(tx, uid));
    const media = await prepareMedia(
      uid,
      uploadId,
      sourceHash(data),
      "private/users/" + uid + "/avatar/" + hash(key),
      "AVATAR",
      uid,
    );
    const result = await once(uid, op, key, data, async (tx) => {
      const old = (await tx.get(db.doc("avatars/" + uid))).data();
      const consume = await consumeUpload(
        tx,
        uid,
        uploadId,
        sourceHash(data),
        "AVATAR",
        null,
      );
      tx.set(db.doc("avatars/" + uid), {
        mediaPath: media.path,
        mimeType: media.mimeType,
        sha256: media.sha256,
        updatedAt: now(),
      });
      if (old?.mediaPath && old.mediaPath !== media.path)
        tx.set(db.doc("cleanupJobs/object_" + hash(old.mediaPath)), {
          kind: "object",
          path: old.mediaPath,
          attempts: 0,
          nextAttemptAt: now(),
        });
      consume();
      return { updated: true };
    });
    await removePending(uid, uploadId);
    return result;
  }
  if (op === "profile.avatarMedia") {
    fields(data, ["ownerUid", "thumbnail"]);
    const ownerUid = id(data, "ownerUid");
    const authorize = async (tx: Transaction) => {
      await activeAccount(tx, uid);
      if (ownerUid !== uid) {
        const membership = await state(tx, uid);
        if (typeof membership.coupleId !== "string")
          return fail("permission-denied", "Avatar unavailable.");
        const couple = await activeCouple(tx, uid, membership.coupleId);
        if (!(couple.memberUids as string[]).includes(ownerUid))
          return fail("permission-denied", "Avatar unavailable.");
      }
      const image = (await tx.get(db.doc("avatars/" + ownerUid))).data();
      if (!image) return fail("not-found", "No avatar selected.");
      return image;
    };
    const image = await db.runTransaction(authorize);
    const result = await mediaResponse(
      image.mediaPath,
      data.thumbnail !== false,
    );
    const current = await db.runTransaction(authorize);
    if (current.mediaPath !== image.mediaPath)
      return fail("not-found", "The avatar changed. Refresh.");
    return result;
  }
  return fail("invalid-argument", "Unknown profile operation.");
}
