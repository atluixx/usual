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
  GoogleAuthProvider,
  linkWithCredential,
  sendPasswordResetEmail,
  sendEmailVerification,
  signInWithEmailAndPassword,
  signInAnonymously,
  signInWithCredential,
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
  query,
  where,
  orderBy,
  limit,
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

test("server-owned pairing state cannot be forged and couple reads require current membership", async () => {
  await environment.withSecurityRulesDisabled(async (context) => {
    const database = context.firestore();
    for (const [uid, coupleId] of [
      ["alice", "first"],
      ["bob", "first"],
      ["charlie", "second"],
      ["dana", "second"],
    ]) {
      await setDoc(doc(database, "pairing/" + uid), {
        coupleId,
        invitationId: null,
        status: "paired",
      });
    }
    await setDoc(doc(database, "couples/first"), {
      memberUids: ["alice", "bob"],
      memberNames: { alice: "Alice", bob: "Bob" },
      status: "active",
    });
    await setDoc(doc(database, "couples/second"), {
      memberUids: ["charlie", "dana"],
      memberNames: { charlie: "Charlie", dana: "Dana" },
      status: "active",
    });
    await setDoc(doc(database, "invitations/private"), {
      creatorUid: "alice",
      claimantUid: "bob",
      status: "claimed",
    });
  });
  const alice = ownerDb();
  const stranger = environment.authenticatedContext("charlie").firestore();
  await assertSucceeds(getDoc(doc(alice, "pairing/alice")));
  await assertSucceeds(getDoc(doc(alice, "couples/first")));
  await assertSucceeds(getDoc(doc(alice, "invitations/private")));
  for (const path of ["pairing/bob", "couples/second"])
    await assertFails(getDoc(doc(alice, path)));
  for (const path of ["pairing/alice", "couples/first", "invitations/private"])
    await assertFails(getDoc(doc(stranger, path)));
  for (const path of [
    "pairing/alice",
    "couples/first",
    "invitations/private",
  ]) {
    await assertFails(
      setDoc(doc(alice, path), {
        coupleId: "first",
        memberUids: ["alice", "charlie"],
        creatorUid: "alice",
      }),
    );
    await assertFails(deleteDoc(doc(alice, path)));
  }
  await assertFails(getDocs(collection(alice, "couples")));
  await assertFails(getDocs(collection(alice, "invitations")));
  await environment.withSecurityRulesDisabled(async (context) => {
    await updateDoc(doc(context.firestore(), "couples/first"), {
      status: "closed",
    });
  });
  await assertFails(getDoc(doc(alice, "couples/first")));
});

test("account deletion tombstones block stale authenticated tokens", async () => {
  await seed();
  await environment.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "accounts/alice"), {
      state: "deleting",
    });
  });
  await assertFails(getDoc(ownRef()));
  await assertFails(
    updateDoc(ownRef(), {
      displayName: "Still here",
      updatedAt: serverTimestamp(),
    }),
  );
  await assertFails(getDoc(doc(ownerDb(), "pairing/alice")));
});

test("gallery queries are bounded and exclude deleted data; neither partner can forge content metadata", async () => {
  await environment.withSecurityRulesDisabled(async (ctx) => {
    const database = ctx.firestore();
    await setDoc(doc(database, "pairing/alice"), { coupleId: "shared" });
    await setDoc(doc(database, "couples/shared"), {
      status: "active",
      memberUids: ["alice", "bob"],
    });
    await setDoc(doc(database, "couples/shared/content/visible"), {
      deleted: false,
      saved: true,
      createdAt: Timestamp.now(),
      caption: "Private",
      senderUid: "bob",
      receiverUid: "alice",
    });
    await setDoc(doc(database, "couples/shared/content/deleted"), {
      deleted: true,
      saved: false,
      createdAt: Timestamp.now(),
    });
  });
  const collectionRef = collection(ownerDb(), "couples/shared/content");
  await assertSucceeds(
    getDocs(
      query(
        collectionRef,
        where("deleted", "==", false),
        orderBy("createdAt", "desc"),
        limit(20),
      ),
    ),
  );
  await assertSucceeds(
    getDocs(
      query(
        collectionRef,
        where("deleted", "==", false),
        where("saved", "==", true),
        orderBy("createdAt", "desc"),
        limit(20),
      ),
    ),
  );
  await assertFails(getDocs(collectionRef));
  await assertFails(
    getDocs(query(collectionRef, where("deleted", "==", false), limit(21))),
  );
  await assertFails(getDoc(doc(ownerDb(), "couples/shared/content/deleted")));
  await assertFails(
    updateDoc(doc(ownerDb(), "couples/shared/content/visible"), {
      saved: false,
    }),
  );
  await assertFails(
    setDoc(doc(ownerDb(), "couples/shared/content/forged"), {
      deleted: false,
      senderUid: "bob",
    }),
  );
  await assertFails(
    getDocs(
      query(
        collection(
          environment.authenticatedContext("stranger").firestore(),
          "couples/shared/content",
        ),
        where("deleted", "==", false),
        limit(20),
      ),
    ),
  );
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

// Unsigned ID tokens are accepted ONLY by the Auth emulator. This never proves
// that a real Android Google account chooser/OAuth configuration works.
const emulatorGoogleCredential = (subject) => {
  const encode = (value) =>
    Buffer.from(JSON.stringify(value)).toString("base64url");
  const token = `${encode({ alg: "none", typ: "JWT" })}.${encode({
    sub: subject,
    iss: "https://accounts.google.com",
    aud: "unused-emulator-client",
    email: `${subject}@example.test`,
    email_verified: true,
    iat: Math.floor(Date.now() / 1000),
    exp: Math.floor(Date.now() / 1000) + 3600,
  })}.`;
  return GoogleAuthProvider.credential(token);
};

const guestClient = (name) => {
  const app = initializeApp(
    { projectId, apiKey: "fake-emulator-api-key" },
    name,
  );
  const auth = getAuth(app);
  connectAuthEmulator(
    auth,
    `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`,
    {
      disableWarnings: true,
    },
  );
  const database = getFirestore(app);
  const [host, port] = process.env.FIRESTORE_EMULATOR_HOST.split(":");
  connectFirestoreEmulator(database, host, Number(port));
  return { app, auth, database };
};

test("anonymous guests reuse their identity and can save only their own private profile", async () => {
  const { app, auth, database } = guestClient("guest-private-profile");
  try {
    const first = await signInAnonymously(auth);
    assert.equal(first.user.isAnonymous, true);
    assert.equal(first.user.email, null);
    assert.equal(first.user.emailVerified, false);
    const uid = first.user.uid;
    const reference = doc(database, `users/${uid}`);
    await assertSucceeds(setDoc(reference, profile(uid)));
    assert.equal((await signInAnonymously(auth)).user.uid, uid);
    assert.equal(
      (await getDocFromServer(reference)).data().displayName,
      "Alice",
    );
    await assertFails(getDocs(collection(database, "users")));
    await assertFails(
      setDoc(doc(database, "users/someone-else"), profile("someone-else")),
    );
    await assertFails(
      setDoc(doc(database, "couples/forged"), {
        members: [uid, "someone-else"],
      }),
    );
    const stranger = environment
      .authenticatedContext("other-guest", {
        firebase: { sign_in_provider: "anonymous" },
      })
      .firestore();
    await assertFails(getDoc(doc(stranger, `users/${uid}`)));
  } finally {
    await signOut(auth);
    await deleteApp(app);
  }
});

test("Google link preserves guest UID and profile; recovery works and a collision keeps another guest", async () => {
  const first = guestClient("guest-google-link");
  const second = guestClient("guest-google-collision");
  try {
    const guest = await signInAnonymously(first.auth);
    const uid = guest.user.uid;
    const reference = doc(first.database, `users/${uid}`);
    await setDoc(reference, profile(uid));
    const credential = emulatorGoogleCredential(
      `google-${crypto.randomUUID()}`,
    );
    const linked = await linkWithCredential(guest.user, credential);
    assert.equal(linked.user.uid, uid);
    assert.equal(linked.user.isAnonymous, false);
    assert.ok(
      linked.user.providerData.some(
        (provider) => provider.providerId === "google.com",
      ),
    );
    assert.equal(
      (await getDocFromServer(reference)).data().displayName,
      "Alice",
    );
    await signOut(first.auth);
    const recovered = await signInWithCredential(first.auth, credential);
    assert.equal(recovered.user.uid, uid);
    assert.equal(
      (await getDocFromServer(reference)).data().displayName,
      "Alice",
    );

    const other = await signInAnonymously(second.auth);
    const otherUid = other.user.uid;
    const otherReference = doc(second.database, `users/${otherUid}`);
    await setDoc(
      otherReference,
      profile(otherUid, { displayName: "Other guest" }),
    );
    await assert.rejects(linkWithCredential(other.user, credential), {
      code: "auth/credential-already-in-use",
    });
    assert.equal(second.auth.currentUser.uid, otherUid);
    assert.equal(second.auth.currentUser.isAnonymous, true);
    assert.equal(
      (await getDocFromServer(otherReference)).data().displayName,
      "Other guest",
    );
    await assertFails(getDocFromServer(doc(second.database, `users/${uid}`)));
  } finally {
    await Promise.all([signOut(first.auth), signOut(second.auth)]);
    await Promise.all([deleteApp(first.app), deleteApp(second.app)]);
  }
});
