#!/usr/bin/env node
import { spawnSync } from "node:child_process";
import { accessSync, constants, readFileSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { delimiter, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const android = join(root, "android");
const apk = join(
  android,
  "app/build/outputs/apk/live/debug/app-live-debug.apk",
);
const help = `Usage: npm run android:install -- [--no-build] [--serial SERIAL]

Build the live debug APK and install/update it using adb install -r.
  --no-build       Install the existing live debug APK instead of rebuilding.
  --serial SERIAL  Select a device (overrides ANDROID_SERIAL).
  --help          Show this help.

ADB discovery: ADB executable path, PATH, ANDROID_HOME, ANDROID_SDK_ROOT,
android/local.properties (sdk.dir), then standard Linux/macOS SDK locations.
Enable USB debugging, connect your phone and accept its debugging prompt.
`;

function executable(path) {
  try {
    accessSync(path, constants.X_OK);
    return statSync(path).isFile();
  } catch {
    return false;
  }
}

function findAdb() {
  if (process.env.ADB) {
    const configured = resolve(process.env.ADB);
    if (!executable(configured))
      throw new Error(`ADB is not an executable file: ${configured}`);
    return configured;
  }
  const sdks = [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT];
  try {
    const properties = readFileSync(join(android, "local.properties"), "utf8");
    const value = properties.match(
      /^[ \t]*sdk\.dir[ \t]*[:=][ \t]*(.*?)[ \t]*$/m,
    )?.[1];
    if (value) {
      // Android Studio writes Java-properties escapes, including spaces/colons.
      const decoded = value.replace(
        /\\u([\da-fA-F]{4})|\\(.)/g,
        (_, hex, char) =>
          hex
            ? String.fromCharCode(Number.parseInt(hex, 16))
            : ({ t: "\t", r: "\r", n: "\n", f: "\f" }[char] ?? char),
      );
      sdks.push(resolve(android, decoded));
    }
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  sdks.push(
    join(homedir(), "Android/Sdk"),
    join(homedir(), "Library/Android/sdk"),
  );
  const candidates = [
    ...(process.env.PATH || "")
      .split(delimiter)
      .map((path) => resolve(path, "adb")),
    ...sdks.filter(Boolean).map((sdk) => resolve(sdk, "platform-tools/adb")),
  ];
  const found = candidates.find(executable);
  if (!found)
    throw new Error(
      "ADB not found. Install Android SDK Platform-Tools and set ADB=/path/to/adb or ANDROID_HOME=/path/to/sdk.",
    );
  return found;
}

function run(command, args, capture = false) {
  const result = spawnSync(command, args, {
    cwd: root,
    encoding: "utf8",
    stdio: capture ? "pipe" : "inherit",
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    if (capture && result.stderr) process.stderr.write(result.stderr);
    throw new Error(
      `${args.includes("install") ? "APK installation" : "Command"} failed (${result.signal || result.status}). See the output above; resolve device, build, signing or version errors before retrying.`,
    );
  }
  return result.stdout || "";
}

function requireApk() {
  try {
    accessSync(apk, constants.R_OK);
    if (statSync(apk).isFile() && statSync(apk).size > 0) return;
  } catch {
    /* Report the expected artifact and remedy below. */
  }
  throw new Error(
    `Live debug APK is missing or unreadable: ${apk}\nRun npm run android:install without --no-build to build it.`,
  );
}

function main() {
  const args = process.argv.slice(2);
  let build = true;
  let serial = process.env.ANDROID_SERIAL;
  while (args.length) {
    const argument = args.shift();
    if (argument === "--help") {
      process.stdout.write(help);
      return;
    }
    if (argument === "--no-build") build = false;
    else if (argument === "--serial") {
      serial = args.shift();
      if (!serial || serial.startsWith("--"))
        throw new Error(
          "--serial requires a device serial. Run adb devices to find it.",
        );
    } else throw new Error(`Unknown argument: ${argument}. Use --help.`);
  }
  if (!build) requireApk();
  const adb = findAdb();
  const devices = run(adb, ["devices"], true)
    .split(/\r?\n/)
    .filter((line) => line.includes("\t"))
    .map((line) => {
      const [id, state] = line.split("\t");
      return { id, state: state.trim() };
    });
  if (!serial) {
    if (!devices.length)
      throw new Error(
        "No Android device connected. Enable USB debugging, connect your phone and accept its debugging prompt.",
      );
    if (devices.length !== 1)
      throw new Error(
        `Multiple devices connected. Choose one with --serial SERIAL:\n${devices.map(({ id, state }) => `  ${id} (${state})`).join("\n")}`,
      );
    serial = devices[0].id;
  }
  const target = devices.find((device) => device.id === serial);
  if (!target)
    throw new Error(
      `Device ${serial} is not connected. Run adb devices and select a connected device.`,
    );
  if (target.state !== "device")
    throw new Error(
      `Device ${serial} is ${target.state}. ${target.state === "unauthorized" ? "Unlock your phone and accept its USB debugging prompt." : "Reconnect it and check adb devices/USB permissions before retrying."}`,
    );
  console.log(`Selected device: ${serial}`);
  if (build) {
    try {
      accessSync(
        join(android, "app/src/live/google-services.json"),
        constants.R_OK,
      );
    } catch {
      throw new Error(
        "Live Firebase client configuration is missing. Follow docs/firebase.md to obtain android/app/src/live/google-services.json, or use --no-build with an existing APK.",
      );
    }
    console.log("Building the live debug APK…");
    run(join(android, "gradlew"), [
      "--no-daemon",
      "-p",
      android,
      ":app:assembleLiveDebug",
    ]);
  }
  requireApk();
  console.log(`Installing/updating ${apk}…`);
  // Pin the original target even if other devices connect while Gradle builds.
  run(adb, ["-s", serial, "install", "-r", apk]);
  console.log(
    `Usual installed/updated on ${serial}. Open Usual on your device.`,
  );
}

try {
  main();
} catch (error) {
  console.error(`Error: ${error.message}`);
  process.exitCode = 1;
}
