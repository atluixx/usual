import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { after, before, beforeEach, test } from "node:test";
import {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} from "@firebase/rules-unit-testing";
import { doc, setDoc, Timestamp } from "firebase/firestore";
import { ref, uploadBytes, getBytes, getDownloadURL } from "firebase/storage";
const projectId = "demo-usual";
for (const key of ["FIRESTORE_EMULATOR_HOST", "FIREBASE_STORAGE_EMULATOR_HOST"])
  assert.match(process.env[key] || "", /^(127\.0\.0\.1|localhost):\d+$/);
let env;
before(async () => {
  env = await initializeTestEnvironment({
    projectId,
    firestore: { rules: await readFile("firestore.rules", "utf8") },
    storage: { rules: await readFile("storage.rules", "utf8") },
  });
});
after(async () => env.cleanup());
beforeEach(async () => {
  await env.clearFirestore();
  await env.clearStorage();
});
const owner = () =>
  env
    .authenticatedContext("alice")
    .storage("gs://demo-usual.firebasestorage.app");
const bytes = new Uint8Array([1, 2, 3]);
const metadata = {
  contentType: "image/png",
  customMetadata: { sha256: "a".repeat(64) },
};
async function permit(overrides = {}) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), "uploads/alice/items/upload"), {
      uid: "alice",
      uploadId: "upload",
      state: "pending",
      bytes: 3,
      sha256: "a".repeat(64),
      expiresAt: Timestamp.fromMillis(Date.now() + 60_000),
      ...overrides,
    });
  });
}
test("only an owner with a live server-issued permit may upload/read staging bytes", async () => {
  const file = ref(owner(), "pending/alice/upload");
  await assertFails(uploadBytes(file, bytes, metadata));
  await permit();
  await assertSucceeds(uploadBytes(file, bytes, metadata));
  await assertSucceeds(getBytes(file));
  const other = env
    .authenticatedContext("bob")
    .storage("gs://demo-usual.firebasestorage.app");
  await assertFails(getBytes(ref(other, "pending/alice/upload")));
  await assertFails(
    uploadBytes(ref(other, "pending/alice/upload"), bytes, metadata),
  );
  await assertFails(
    getBytes(
      ref(
        env
          .unauthenticatedContext()
          .storage("gs://demo-usual.firebasestorage.app"),
        "pending/alice/upload",
      ),
    ),
  );
});
test("expired/consumed permits, forged checksums/types and deletion tombstones are denied", async () => {
  const file = ref(owner(), "pending/alice/upload");
  await permit({ expiresAt: Timestamp.fromMillis(0) });
  await assertFails(uploadBytes(file, bytes, metadata));
  await permit({ state: "consumed" });
  await assertFails(uploadBytes(file, bytes, metadata));
  await permit();
  await assertFails(
    uploadBytes(file, bytes, { ...metadata, contentType: "text/html" }),
  );
  await assertFails(
    uploadBytes(file, bytes, {
      ...metadata,
      customMetadata: { sha256: "b".repeat(64) },
    }),
  );
  await assertFails(uploadBytes(file, new Uint8Array(4), metadata));
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), "accounts/alice"), { state: "deleting" });
  });
  await assertFails(uploadBytes(file, bytes, metadata));
});
test("normalized private media cannot be read, replaced or given a public download URL by a client", async () => {
  const path = "private/couples/couple/content/item";
  await env.withSecurityRulesDisabled(async (ctx) => {
    await uploadBytes(
      ref(ctx.storage("gs://demo-usual.firebasestorage.app"), path),
      bytes,
      metadata,
    );
  });
  await assertFails(getBytes(ref(owner(), path)));
  await assertFails(getDownloadURL(ref(owner(), path)));
  await assertFails(uploadBytes(ref(owner(), path), bytes, metadata));
});
