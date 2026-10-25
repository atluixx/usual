import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { deflateSync } from "node:zlib";

const root = resolve(fileURLToPath(new URL("..", import.meta.url)));
const temporary = mkdtempSync(join(tmpdir(), "usual-branding-"));
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
assert.ok(sdk, "Set ANDROID_HOME to the Android SDK for APK branding checks.");
const aapt = join(sdk, "build-tools/36.0.0/aapt2");
const apk = join(
  root,
  "android/app/build/outputs/apk/emulator/debug/app-emulator-debug.apk",
);
const defaults = { APP_NAME: "Usual", APP_LOGO: "assets/logo.png" };
const invoke = (task, branding = defaults, success = true) => {
  const result = spawnSync("./gradlew", ["--no-daemon", task], {
    cwd: join(root, "android"),
    env: { ...process.env, ...branding },
    encoding: "utf8",
    maxBuffer: 8 * 1024 * 1024,
  });
  if (success && result.status !== 0)
    throw new Error(result.stdout + result.stderr);
  if (!success) {
    assert.notEqual(result.status, 0);
    return result.stdout + result.stderr;
  }
};
const inspect = () => {
  const result = spawnSync(aapt, ["dump", "badging", apk], {
    encoding: "utf8",
  });
  assert.equal(result.status, 0, result.stderr);
  const entries = spawnSync("jar", ["tf", apk], { encoding: "utf8" });
  assert.equal(entries.status, 0, entries.stderr);
  const path = entries.stdout
    .split("\n")
    .find((entry) => entry.endsWith("/brand_logo.png"));
  assert.ok(path, "The configured brand logo must be packaged in the APK.");
  const packaged = spawnSync("jar", ["xf", apk, path], { cwd: temporary });
  assert.equal(packaged.status, 0);
  return {
    packagedLogo: readFileSync(join(temporary, path)),
    id: result.stdout.match(/package: name='([^']+)'/)[1],
    label: result.stdout
      .split("\n")
      .find((line) => line.startsWith("application-label:'"))
      .slice(19, -1),
    logo: readFileSync(
      join(
        root,
        "android/app/build/generated/branding/res/drawable-nodpi/brand_logo.png",
      ),
    ),
  };
};
// A deterministic synthetic PNG fixture avoids another image-processing dependency.
function chunk(type, data) {
  const bytes = Buffer.concat([Buffer.from(type), data]);
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++)
      crc = crc & 1 ? (crc >>> 1) ^ 0xedb88320 : crc >>> 1;
  }
  const length = Buffer.alloc(4),
    checksum = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  checksum.writeUInt32BE((crc ^ 0xffffffff) >>> 0);
  return Buffer.concat([length, bytes, checksum]);
}
const header = Buffer.alloc(13);
header.writeUInt32BE(256, 0);
header.writeUInt32BE(256, 4);
header[8] = 8;
header[9] = 6;
const rows = Buffer.alloc(256 * (1 + 256 * 4));
for (let y = 0; y < 256; y++)
  for (let x = 0; x < 256; x++)
    rows.set([30, 100, 210, 255], y * 1025 + 1 + x * 4);
const logo = join(temporary, "custom.png");
writeFileSync(
  logo,
  Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk("IHDR", header),
    chunk("IDAT", deflateSync(rows)),
    chunk("IEND", Buffer.alloc(0)),
  ]),
);
try {
  invoke(":app:assembleEmulatorDebug");
  const first = inspect();
  assert.equal(first.label, "Usual");
  assert.equal(first.id, "com.okoda.usual.emulator");
  invoke(":app:assembleEmulatorDebug", {
    APP_NAME: "Our Usual & Together",
    APP_LOGO: logo,
  });
  const second = inspect();
  assert.equal(second.label, "Our Usual & Together");
  assert.equal(second.id, first.id);
  assert.notDeepEqual(second.logo, first.logo);
  assert.notDeepEqual(second.packagedLogo, first.packagedLogo);
  assert.match(
    invoke(
      ":app:generateBranding",
      { ...defaults, APP_LOGO: join(temporary, "missing.png") },
      false,
    ),
    /APP_LOGO/,
  );
  writeFileSync(join(temporary, "damaged.png"), "not a PNG");
  assert.match(
    invoke(
      ":app:generateBranding",
      { ...defaults, APP_LOGO: join(temporary, "damaged.png") },
      false,
    ),
    /APP_LOGO/,
  );
  assert.match(
    invoke(":app:generateBranding", { ...defaults, APP_NAME: "" }, false),
    /APP_NAME/,
  );
  console.log(
    "Branding APK checks passed: default/custom label and logo, stable ID, invalid/missing assets and blank name.",
  );
} finally {
  rmSync(temporary, { recursive: true, force: true });
  invoke(":app:assembleEmulatorDebug");
}
