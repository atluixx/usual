import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
  copyFileSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

function fixture(t) {
  const directory = mkdtempSync(join(tmpdir(), "usual install "));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const root = join(directory, "repository with spaces");
  const sdk = join(directory, "Android SDK");
  const bin = join(directory, "bin");
  const log = join(directory, "commands.jsonl");
  const apk = join(
    root,
    "android/app/build/outputs/apk/live/debug/app-live-debug.apk",
  );
  for (const path of [
    join(root, "scripts"),
    join(root, "android/app/src/live"),
    join(sdk, "platform-tools"),
    bin,
  ])
    mkdirSync(path, { recursive: true });
  copyFileSync(
    fileURLToPath(new URL("../scripts/install-apk.mjs", import.meta.url)),
    join(root, "scripts/install-apk.mjs"),
  );
  symlinkSync(process.execPath, join(bin, "node"));
  writeFileSync(
    join(root, "android/local.properties"),
    `sdk.dir=${sdk.replaceAll(" ", "\\ ")}\n`,
  );
  writeFileSync(join(root, "android/app/src/live/google-services.json"), "{}");
  const adb = join(sdk, "platform-tools/adb");
  writeFileSync(
    adb,
    `#!/usr/bin/env node
const fs = require('node:fs');
const args = process.argv.slice(2);
fs.appendFileSync(process.env.INSTALL_TEST_LOG, JSON.stringify({ tool: 'adb', args }) + '\\n');
if (args[0] === 'devices') console.log('List of devices attached\\n' + process.env.INSTALL_TEST_DEVICES);
else if (args[0] === '-s' && args[2] === 'install' && args[3] === '-r') {
  console.log(process.env.INSTALL_TEST_FAIL_INSTALL ? 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]' : 'Success');
  process.exitCode = process.env.INSTALL_TEST_FAIL_INSTALL ? 1 : 0;
} else { console.error('Unexpected ADB action'); process.exitCode = 9; }
`,
    { mode: 0o755 },
  );
  writeFileSync(
    join(root, "android/gradlew"),
    `#!/usr/bin/env node
const fs = require('node:fs');
const path = require('node:path');
fs.appendFileSync(process.env.INSTALL_TEST_LOG, JSON.stringify({ tool: 'gradle', args: process.argv.slice(2) }) + '\\n');
if (process.env.INSTALL_TEST_FAIL_BUILD) process.exitCode = 1;
else { fs.mkdirSync(path.dirname(process.env.INSTALL_TEST_APK), { recursive: true }); fs.writeFileSync(process.env.INSTALL_TEST_APK, 'synthetic APK'); }
`,
    { mode: 0o755 },
  );
  return {
    root,
    adb,
    apk,
    existingApk() {
      mkdirSync(join(root, "android/app/build/outputs/apk/live/debug"), {
        recursive: true,
      });
      writeFileSync(apk, "existing APK");
    },
    calls() {
      try {
        return readFileSync(log, "utf8").trim().split("\n").map(JSON.parse);
      } catch (error) {
        if (error.code === "ENOENT") return [];
        throw error;
      }
    },
    invoke(args = [], environment = {}) {
      return spawnSync(
        process.execPath,
        [join(root, "scripts/install-apk.mjs"), ...args],
        {
          // Neither PATH nor HOME can discover a real ADB: every test is isolated.
          cwd: directory,
          env: {
            ...process.env,
            PATH: bin,
            HOME: directory,
            ADB: "",
            ANDROID_HOME: "",
            ANDROID_SDK_ROOT: "",
            ANDROID_SERIAL: "",
            INSTALL_TEST_LOG: log,
            INSTALL_TEST_APK: apk,
            INSTALL_TEST_DEVICES: "phone-one\tdevice\n",
            ...environment,
          },
          encoding: "utf8",
        },
      );
    },
  };
}

test("builds the live APK from another directory, discovers an escaped SDK path, then installs with data-preserving replacement", (t) => {
  const f = fixture(t);
  const result = f.invoke();
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(f.calls(), [
    { tool: "adb", args: ["devices"] },
    {
      tool: "gradle",
      args: [
        "--no-daemon",
        "-p",
        join(f.root, "android"),
        ":app:assembleLiveDebug",
      ],
    },
    { tool: "adb", args: ["-s", "phone-one", "install", "-r", f.apk] },
  ]);
});

test("existing-APK updates target the explicit serial ahead of ANDROID_SERIAL without rebuilding", (t) => {
  const f = fixture(t);
  f.existingApk();
  const result = f.invoke(["--no-build", "--serial", "phone-two"], {
    ADB: f.adb,
    ANDROID_SERIAL: "phone-one",
    INSTALL_TEST_DEVICES: "phone-one\tdevice\nphone-two\tdevice\n",
  });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(f.calls(), [
    { tool: "adb", args: ["devices"] },
    { tool: "adb", args: ["-s", "phone-two", "install", "-r", f.apk] },
  ]);
});

test("ANDROID_SERIAL selects the requested device among several", (t) => {
  const f = fixture(t);
  f.existingApk();
  const result = f.invoke(["--no-build"], {
    ANDROID_SERIAL: "phone-two",
    INSTALL_TEST_DEVICES: "phone-one\tdevice\nphone-two\tdevice\n",
  });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(f.calls().at(-1).args[1], "phone-two");
});

test("unavailable, ambiguous and unauthorized targets stop before Gradle or installation", async (t) => {
  for (const [devices, args, error] of [
    ["", [], /No Android device/],
    ["phone-one\tunauthorized\n", [], /Unlock your phone/],
    ["phone-one\toffline\n", [], /offline/],
    ["phone-one\tno permissions\n", [], /USB permissions/],
    ["phone-one\tdevice\nphone-two\tdevice\n", [], /Multiple devices/],
    ["phone-one\tdevice\n", ["--serial", "missing"], /not connected/],
  ])
    await t.test(error.source, (t) => {
      const f = fixture(t);
      const result = f.invoke(args, { INSTALL_TEST_DEVICES: devices });
      assert.equal(result.status, 1);
      assert.match(result.stderr, error);
      assert.deepEqual(f.calls(), [{ tool: "adb", args: ["devices"] }]);
    });
});

test("failed installation reports failure and never falls back to uninstalling or clearing data", (t) => {
  const f = fixture(t);
  f.existingApk();
  const result = f.invoke(["--no-build"], { INSTALL_TEST_FAIL_INSTALL: "1" });
  assert.equal(result.status, 1);
  assert.match(result.stdout, /UPDATE_INCOMPATIBLE/);
  assert.match(result.stderr, /APK installation failed/);
  assert.deepEqual(f.calls(), [
    { tool: "adb", args: ["devices"] },
    { tool: "adb", args: ["-s", "phone-one", "install", "-r", f.apk] },
  ]);
});

test("failed build prevents installation even when an older APK exists", (t) => {
  const f = fixture(t);
  f.existingApk();
  const result = f.invoke([], { INSTALL_TEST_FAIL_BUILD: "1" });
  assert.equal(result.status, 1);
  assert.deepEqual(
    f.calls().map(({ tool }) => tool),
    ["adb", "gradle"],
  );
});

test("missing APK, invalid arguments and invalid explicit ADB fail without device actions", async (t) => {
  for (const [args, environment, error] of [
    [["--no-build"], {}, /APK is missing or unreadable/],
    [["--serial"], {}, /requires a device serial/],
    [["--unknown"], {}, /Unknown argument/],
    [[], { ADB: "/missing/adb" }, /ADB is not an executable/],
  ])
    await t.test(error.source, (t) => {
      const f = fixture(t);
      const result = f.invoke(args, environment);
      assert.equal(result.status, 1);
      assert.match(result.stderr, error);
      assert.deepEqual(f.calls(), []);
    });
});

test("help works without an APK, SDK or connected device", (t) => {
  const f = fixture(t);
  const result = f.invoke(["--help"]);
  assert.equal(result.status, 0);
  assert.match(result.stdout, /android:install/);
  assert.deepEqual(f.calls(), []);
});
