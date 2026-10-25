import { createHash } from "node:crypto";
import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore, Timestamp, Transaction } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";

const projectId = process.env.GCLOUD_PROJECT || process.env.GCP_PROJECT;
if (
  [
    "FIRESTORE_EMULATOR_HOST",
    "FIREBASE_AUTH_EMULATOR_HOST",
    "FIREBASE_STORAGE_EMULATOR_HOST",
  ].some((key) => process.env[key]) &&
  !projectId?.startsWith("demo-")
)
  throw new Error("Emulator trust requires an explicit demo project.");
if (!getApps().length) initializeApp();
export const db = getFirestore();
export type Args = Record<string, unknown>;
export type Result = Record<string, unknown>;
export const hash = (value: string | Buffer): string =>
  createHash("sha256").update(value).digest("hex");
export const now = (): Timestamp => Timestamp.now();
export const fail = (
  code: ConstructorParameters<typeof HttpsError>[0],
  message: string,
): never => {
  throw new HttpsError(code, message, { safeMessage: message });
};
export function object(value: unknown): Args {
  if (!value || typeof value !== "object" || Array.isArray(value))
    return fail("invalid-argument", "Expected an object.");
  return value as Args;
}
export function fields(data: Args, allowed: string[]): void {
  if (Object.keys(data).some((key) => !allowed.includes(key)))
    fail("invalid-argument", "Unexpected field.");
}
export function text(data: Args, key: string, min = 1, max = 128): string {
  const value = data[key];
  if (typeof value !== "string" || value.length < min || value.length > max)
    return fail("invalid-argument", "Invalid " + key + ".");
  return value;
}
export function integer(
  data: Args,
  key: string,
  min: number,
  max: number,
): number {
  const value = data[key];
  if (
    typeof value !== "number" ||
    !Number.isSafeInteger(value) ||
    value < min ||
    value > max
  )
    return fail("invalid-argument", "Invalid " + key + ".");
  return value;
}
export function id(data: Args, key: string): string {
  const value = text(data, key, 1, 128);
  if (!/^[a-zA-Z0-9_-]+$/.test(value))
    return fail("invalid-argument", "Invalid identifier.");
  return value;
}
export function confirmed(data: Args): void {
  if (data.confirm !== true)
    fail("failed-precondition", "Explicit confirmation is required.");
}
export const stateRef = (uid: string) => db.doc("pairing/" + uid);
export async function state(tx: Transaction, uid: string): Promise<Args> {
  return (
    (await tx.get(stateRef(uid))).data() || {
      coupleId: null,
      invitationId: null,
      status: "unpaired",
    }
  );
}
export async function activeAccount(
  tx: Transaction,
  uid: string,
): Promise<void> {
  const account = (await tx.get(db.doc("accounts/" + uid))).data();
  if (account && account.state !== "active")
    fail("permission-denied", "This account is being deleted.");
}
export async function activeCouple(
  tx: Transaction,
  uid: string,
  coupleId: string,
): Promise<Args> {
  const [membership, couple] = await Promise.all([
    state(tx, uid),
    tx.get(db.doc("couples/" + coupleId)),
  ]);
  await activeAccount(tx, uid);
  const data = couple.data();
  if (
    membership.coupleId !== coupleId ||
    data?.status !== "active" ||
    !Array.isArray(data.memberUids) ||
    data.memberUids.length !== 2 ||
    !data.memberUids.includes(uid)
  ) {
    return fail("permission-denied", "This couple is not available.");
  }
  return data;
}
export function unpaired(membership: Args): void {
  if (membership.coupleId)
    fail("failed-precondition", "Unpair before starting another invitation.");
}
function canonical(value: unknown): string {
  if (Array.isArray(value)) return "[" + value.map(canonical).join(",") + "]";
  if (value && typeof value === "object")
    return (
      "{" +
      Object.entries(value)
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([k, v]) => JSON.stringify(k) + ":" + canonical(v))
        .join(",") +
      "}"
    );
  return JSON.stringify(value);
}
export async function once(
  uid: string,
  op: string,
  requestId: string,
  data: Args,
  work: (tx: Transaction) => Promise<Result>,
  replay?: (tx: Transaction, result: Result) => Promise<void>,
): Promise<Result> {
  if (!/^[a-zA-Z0-9_-]{16,80}$/.test(requestId))
    return fail("invalid-argument", "A stable request ID is required.");
  const reference = db.doc("operations/" + uid + "/requests/" + requestId);
  const digest = hash(canonical({ op, data }));
  return db.runTransaction(async (tx) => {
    await activeAccount(tx, uid);
    const previous = (await tx.get(reference)).data();
    if (previous) {
      if (previous.digest !== digest)
        return fail(
          "already-exists",
          "This request ID was used for another action.",
        );
      if (replay) await replay(tx, previous.result);
      return previous.result as Result;
    }
    const result = await work(tx);
    tx.create(reference, { op, digest, result, createdAt: now() });
    return result;
  });
}
export async function rateLimit(uid: string): Promise<void> {
  const reference = db.doc("limits/" + uid);
  await db.runTransaction(async (tx) => {
    const previous = (await tx.get(reference)).data() || {
      window: 0,
      count: 0,
    };
    const time = Date.now();
    const sameWindow = previous?.window > time - 60_000;
    const count = sameWindow ? previous.count + 1 : 1;
    if (count > 60)
      fail("resource-exhausted", "Too many attempts. Wait a minute and retry.");
    tx.set(reference, { window: sameWindow ? previous.window : time, count });
  });
}
export function clearState(tx: Transaction, uid: string): void {
  tx.set(stateRef(uid), {
    coupleId: null,
    invitationId: null,
    status: "unpaired",
    updatedAt: now(),
  });
}
