import assert from "node:assert/strict";
import { test } from "node:test";
import { randomBytes } from "node:crypto";
import { setTimeout as delay } from "node:timers/promises";
import { createRequire } from "node:module";
import { initializeApp, deleteApp } from "firebase/app";
import {
  getAuth,
  connectAuthEmulator,
  signInAnonymously,
  signOut,
  getIdTokenResult,
} from "firebase/auth";
import {
  getFunctions,
  connectFunctionsEmulator,
  httpsCallable,
} from "firebase/functions";
import {
  getFirestore,
  connectFirestoreEmulator,
  doc,
  getDocFromServer,
} from "firebase/firestore";
assert.match(
  process.env.FIREBASE_AUTH_EMULATOR_HOST || "",
  /^(127\.0\.0\.1|localhost):\d+$/,
);
const require = createRequire(
  new URL("../functions/package.json", import.meta.url),
);
const { initializeApp: initializeAdminApp } = require("firebase-admin/app");
const { getAuth: adminAuth } = require("firebase-admin/auth");
initializeAdminApp({ projectId: "demo-usual" });
assert.match(
  process.env.FIRESTORE_EMULATOR_HOST || "",
  /^(127\.0\.0\.1|localhost):\d+$/,
);
function client(name) {
  const app = initializeApp(
    { projectId: "demo-usual", apiKey: "fake-emulator-api-key" },
    name,
  );
  const auth = getAuth(app);
  connectAuthEmulator(
    auth,
    "http://" + process.env.FIREBASE_AUTH_EMULATOR_HOST,
    { disableWarnings: true },
  );
  const functions = getFunctions(app, "southamerica-east1");
  connectFunctionsEmulator(functions, "127.0.0.1", 5001);
  const database = getFirestore(app);
  const [host, port] = process.env.FIRESTORE_EMULATOR_HOST.split(":");
  connectFirestoreEmulator(database, host, Number(port));
  const api = httpsCallable(functions, "api");
  const call = (op, data, requestId = crypto.randomUUID()) =>
    api({ v: 1, op, requestId, data }).then((value) => value.data);
  return { app, auth, database, call };
}
test("real callable protocol pairs two anonymous SDK users and denies unauthenticated/third-user actions", async () => {
  const a = client("callable-alice");
  const b = client("callable-bob");
  const c = client("callable-stranger");
  try {
    await assert.rejects(
      a.call("pair.create", { code: randomBytes(12).toString("hex") }),
      { code: "functions/unauthenticated" },
    );
    await Promise.all([a, b, c].map((value) => signInAnonymously(value.auth)));
    const code = randomBytes(12).toString("hex").toUpperCase();
    const invitation = await a.call("pair.create", { code });
    await b.call("pair.claim", { code });
    await assert.rejects(
      c.call("pair.confirm", {
        invitationId: invitation.invitationId,
        confirm: true,
      }),
      { code: "functions/permission-denied" },
    );
    const result = await a.call("pair.confirm", {
      invitationId: invitation.invitationId,
      confirm: true,
    });
    const own = doc(b.database, "couples/" + result.coupleId);
    assert.equal((await getDocFromServer(own)).data().memberUids.length, 2);
    await assert.rejects(
      getDocFromServer(doc(c.database, "couples/" + result.coupleId)),
    );
    await b.call("pair.end", { coupleId: result.coupleId, confirm: true });
    await assert.rejects(getDocFromServer(own));
    // Firebase compares auth_time and revocation timestamps at whole-second
    // precision. A same-second revocation is not strictly later than sign-in.
    const { claims } = await getIdTokenResult(a.auth.currentUser);
    const authenticatedAt = Number(claims.auth_time) * 1000;
    await delay(Math.max(0, authenticatedAt + 1000 - Date.now()) + 25);
    await adminAuth().revokeRefreshTokens(a.auth.currentUser.uid);
    const revoked = await adminAuth().getUser(a.auth.currentUser.uid);
    assert.ok(
      new Date(revoked.tokensValidAfterTime).getTime() > authenticatedAt,
    );
    await assert.rejects(
      a.call("pair.create", { code: randomBytes(12).toString("hex") }),
      { code: "functions/unauthenticated" },
    );
    await adminAuth().updateUser(c.auth.currentUser.uid, { disabled: true });
    await assert.rejects(
      c.call("pair.create", { code: randomBytes(12).toString("hex") }),
      { code: "functions/unauthenticated" },
    );
  } finally {
    await Promise.all([a, b, c].map((value) => signOut(value.auth)));
    await Promise.all([a, b, c].map((value) => deleteApp(value.app)));
  }
});
