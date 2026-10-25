import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { after, before, beforeEach, test } from "node:test";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from "@firebase/rules-unit-testing";
import { deleteApp, initializeApp } from "firebase/app";
import {
  connectAuthEmulator,
  createUserWithEmailAndPassword,
  getAuth,
  sendPasswordResetEmail,
  sendEmailVerification,
  signInWithEmailAndPassword,
  signOut,
} from "firebase/auth";
import {
  collection,
  connectFirestoreEmulator,
  deleteDoc,
  deleteField,
  doc,
  getDoc,
  getDocFromServer,
  getDocs,
  getFirestore,
  serverTimestamp,
  setDoc,
  setLogLevel,
  Timestamp,
  updateDoc,
} from "firebase/firestore";

const projectId = "demo-usual";
setLogLevel("silent");
for (const name of ["FIRESTORE_EMULATOR_HOST", "FIREBASE_AUTH_EMULATOR_HOST"]) {
  assert.match(
    process.env[name] ?? "",
    /^(127\.0\.0\.1|localhost):\d+$/,
    `${name} must point to a local emulator`,
  );
}

let environment;
const profile = (uid = "alice", overrides = {}) => ({
  uid,
  displayName: "Alice",
  createdAt: serverTimestamp(),
  updatedAt: serverTimestamp(),
  schemaVersion: 1,
  ...overrides,
});
const ownerDb = () => environment.authenticatedContext("alice").firestore();
const ownRef = (database = ownerDb()) => doc(database, "users/alice");
const seed = async () => assertSucceeds(setDoc(ownRef(), profile()));

before(async () => {
  environment = await initializeTestEnvironment({
    projectId,
    firestore: {
      rules: await readFile(
        new URL("../firestore.rules", import.meta.url),
        "utf8",
      ),
    },
  });
});
beforeEach(async () => environment.clearFirestore());
after(async () => environment?.cleanup());

test("owner creates, reads, and updates a profile without changing creation time", async () => {
  const reference = ownRef();
  await assertSucceeds(setDoc(reference, profile()));
  const createdAt = (await getDoc(reference)).data().createdAt;
  await assertSucceeds(
    updateDoc(reference, {
      displayName: "A new name",
      updatedAt: serverTimestamp(),
    }),
  );
  const saved = (await getDoc(reference)).data();
  assert.equal(saved.displayName, "A new name");
  assert.ok(saved.createdAt.isEqual(createdAt));
});

test("unauthenticated users cannot read, list, or write profiles", async () => {
  await seed();
  const database = environment.unauthenticatedContext().firestore();
  await assertFails(getDoc(ownRef(database)));
  await assertFails(getDocs(collection(database, "users")));
  await assertFails(setDoc(ownRef(database), profile()));
});

test("a different user cannot access or overwrite another profile", async () => {
  await seed();
  const database = environment.authenticatedContext("bob").firestore();
  await assertFails(getDoc(ownRef(database)));
  await assertFails(
    updateDoc(ownRef(database), {
      displayName: "Bob",
      updatedAt: serverTimestamp(),
    }),
  );
  await assertFails(deleteDoc(ownRef(database)));
  await assertFails(setDoc(doc(database, "users/charlie"), profile("charlie")));
});

test("owners cannot list private users or delete profiles directly", async () => {
  await seed();
  await assertFails(getDocs(collection(ownerDb(), "users")));
  await assertFails(deleteDoc(ownRef()));
});

test("create rejects forged ownership, extra privileges, PII, and unknown fields", async () => {
  for (const overrides of [
    { uid: "bob" },
    { role: "admin" },
    { coupleId: "another-couple" },
    { email: "private@example.test" },
    { extraData: "injected" },
    { schemaVersion: 2 },
  ])
    await assertFails(setDoc(ownRef(), profile("alice", overrides)));
});

test("create validates all fields, bounds, and server timestamps", async () => {
  for (const overrides of [
    { displayName: "" },
    { displayName: "   " },
    { displayName: "\u00a0\u3000" },
    { displayName: "x".repeat(81) },
    { displayName: "x".repeat(100_000) },
    { displayName: 42 },
    { uid: 42 },
    { schemaVersion: "1" },
    { createdAt: Timestamp.fromMillis(0) },
    { updatedAt: Timestamp.fromMillis(0) },
    { createdAt: "2026-10-25" },
    { updatedAt: Timestamp.fromMillis(Date.now() + 86_400_000) },
  ])
    await assertFails(setDoc(ownRef(), profile("alice", overrides)));
  for (const missing of Object.keys(profile())) {
    const data = profile();
    delete data[missing];
    await assertFails(setDoc(ownRef(), data));
  }
});

test("updates cannot hijack ownership, alter immutable fields, or bypass validation", async () => {
  await seed();
  for (const changes of [
    { uid: "bob" },
    { createdAt: Timestamp.fromMillis(0) },
    { schemaVersion: 2 },
    { role: "admin" },
    { displayName: [] },
    { displayName: "x".repeat(81) },
    { displayName: " " },
    { displayName: deleteField() },
    { updatedAt: Timestamp.fromMillis(0) },
    { uid: deleteField() },
    { createdAt: deleteField() },
  ])
    await assertFails(
      updateDoc(ownRef(), { updatedAt: serverTimestamp(), ...changes }),
    );
});

test("unknown collections and orphaned user subcollections stay inaccessible", async () => {
  const database = ownerDb();
  for (const path of [
    "couples/other",
    "content/private",
    "games/match",
    "users/alice/secrets/key",
  ]) {
    await assertFails(getDoc(doc(database, path)));
    await assertFails(setDoc(doc(database, path), { uid: "alice" }));
  }
});

test("email/password identity persists a private profile across sign-out/sign-in", async () => {
  const app = initializeApp(
    { projectId, apiKey: "fake-emulator-api-key" },
    "auth-integration",
  );
  const auth = getAuth(app);
  connectAuthEmulator(
    auth,
    `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`,
    { disableWarnings: true },
  );
  const database = getFirestore(app);
  const [host, port] = process.env.FIRESTORE_EMULATOR_HOST.split(":");
  connectFirestoreEmulator(database, host, Number(port));
  const email = `test-${crypto.randomUUID()}@example.test`;
  const password = "Emulator-password-42!";
  try {
    const first = await createUserWithEmailAndPassword(auth, email, password);
    const uid = first.user.uid;
    assert.equal(first.user.isAnonymous, false);
    assert.equal(first.user.email, email);
    const reference = doc(database, `users/${uid}`);
    await assertSucceeds(setDoc(reference, profile(uid)));
    await sendEmailVerification(first.user);
    await sendPasswordResetEmail(auth, email);
    await signOut(auth);
    assert.equal(auth.currentUser, null);
    await assertFails(getDocFromServer(reference));
    await assert.rejects(
      signInWithEmailAndPassword(auth, email, "wrong-password"),
    );
    const second = await signInWithEmailAndPassword(auth, email, password);
    assert.equal(second.user.uid, uid);
    assert.equal(
      (await getDocFromServer(reference)).data().displayName,
      "Alice",
    );
    const stranger = environment.authenticatedContext("stranger").firestore();
    await assertFails(getDoc(doc(stranger, `users/${uid}`)));
  } finally {
    await signOut(auth);
    await deleteApp(app);
  }
});
