import { getAuth } from "firebase-admin/auth";
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import * as functionsV1 from "firebase-functions/v1";
import { error as logError } from "firebase-functions/logger";
import {
  Args,
  confirmed,
  fail,
  fields,
  object,
  rateLimit,
  Result,
  text,
} from "./core.js";
import { deleteIdentity, pairing } from "./pairing.js";
import { cleanup } from "./maintenance.js";
import { content, avatar, visibleContent } from "./content.js";
import { authorizeUpload, discardUpload } from "./uploads.js";
import { activeAccount, activeCouple, db, id } from "./core.js";
import { games } from "./games.js";
import {
  notifications,
  dispatch,
  retryNotifications,
} from "./notifications.js";

export async function execute(uid: string, input: unknown): Promise<Result> {
  const envelope = object(input);
  fields(envelope, ["v", "op", "requestId", "data", "actorUid"]);
  if (envelope.actorUid !== undefined && envelope.actorUid !== uid)
    return fail(
      "unauthenticated",
      "Your session changed. Retry from the current identity.",
    );
  if (envelope.v !== 1 || Buffer.byteLength(JSON.stringify(envelope)) > 32_768)
    return fail("invalid-argument", "Unsupported or oversized request.");
  const op = text(envelope, "op", 1, 64);
  const key = text(envelope, "requestId", 16, 80);
  const data: Args = object(envelope.data);
  if (op.startsWith("pair.")) return pairing(uid, op, key, data);
  if (op.startsWith("content.")) return content(uid, op, key, data);
  if (op.startsWith("game.")) return games(uid, op, key, data);
  if (
    [
      "preferences.set",
      "device.register",
      "device.unregister",
      "notification.resolve",
      "widget.snapshot",
    ].includes(op)
  )
    return notifications(uid, op, key, data);
  if (op.startsWith("profile.avatar")) return avatar(uid, op, key, data);
  if (op === "media.authorize") return authorizeUpload(uid, data);
  if (op === "media.discard") return discardUpload(uid, data);
  if (op === "operation.status") {
    fields(data, ["requestId", "coupleId"]);
    const original = id(data, "requestId");
    return db.runTransaction(async (tx) => {
      await activeAccount(tx, uid);
      const previous = (
        await tx.get(db.doc("operations/" + uid + "/requests/" + original))
      ).data();
      if (
        !previous ||
        !["content.send", "profile.avatar", "game.draw"].includes(previous.op)
      )
        return { completed: false };
      const result = previous.result;
      if (result.coupleId) {
        if (data.coupleId !== result.coupleId)
          return fail(
            "permission-denied",
            "This send belongs to another couple.",
          );
        await activeCouple(tx, uid, result.coupleId);
        if (result.contentId)
          await visibleContent(tx, uid, result.coupleId, result.contentId);
      }
      return { completed: true, result };
    });
  }
  if (op === "identity.delete") {
    fields(data, ["confirm"]);
    confirmed(data);
    await deleteIdentity(uid);
    return { deleting: true };
  }
  return fail("invalid-argument", "Unknown operation.");
}
export const api = onCall(
  {
    region: "southamerica-east1",
    maxInstances: 3,
    concurrency: 1,
    memory: "512MiB",
    timeoutSeconds: 60,
  },
  async (request) => {
    if (!request.auth)
      return fail("unauthenticated", "Start a guest session or sign in.");
    const bearer = request.rawRequest.headers.authorization?.replace(
      /^Bearer /i,
      "",
    );
    if (!bearer) return fail("unauthenticated", "A valid session is required.");
    try {
      const verified = await getAuth().verifyIdToken(bearer, true);
      if (verified.uid !== request.auth.uid)
        return fail("unauthenticated", "Session mismatch.");
      await rateLimit(verified.uid);
      return await execute(verified.uid, request.data);
    } catch (cause) {
      if (cause instanceof HttpsError) throw cause;
      const code = (cause as { code?: string }).code || "";
      if (code.startsWith("auth/"))
        return fail("unauthenticated", "This session is no longer valid.");
      logError("operation_failed", { code: "internal" });
      return fail("internal", "The operation could not finish. Please retry.");
    }
  },
);
export const maintenance = onSchedule(
  {
    schedule: "every 5 minutes",
    region: "southamerica-east1",
    maxInstances: 1,
    concurrency: 1,
    memory: "512MiB",
    timeoutSeconds: 540,
  },
  async () => {
    await cleanup();
    await retryNotifications();
  },
);
export const deliverEvent = onDocumentCreated(
  {
    document: "deliveryEvents/{eventId}",
    region: "southamerica-east1",
    maxInstances: 2,
    concurrency: 1,
  },
  async (event) => {
    await dispatch(event.params.eventId);
  },
);
export const identityDeleted = functionsV1
  .region("southamerica-east1")
  .auth.user()
  .onDelete(async (user) => {
    await deleteIdentity(user.uid);
  });
