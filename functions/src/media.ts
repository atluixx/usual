import sharp from "sharp";
import { getStorage } from "firebase-admin/storage";
import { Args, db, fail, hash, text } from "./core.js";

export const MAX_MEDIA_BYTES = 4 * 1024 * 1024;
sharp.cache(false);
sharp.concurrency(1);
export function mediaBucket() {
  const project = process.env.GCLOUD_PROJECT || process.env.GCP_PROJECT;
  if (!project) throw new Error("An explicit project is required.");
  if (
    project.startsWith("demo-") &&
    !process.env.FIREBASE_STORAGE_EMULATOR_HOST
  )
    throw new Error("Demo media requires the Storage emulator.");
  return getStorage().bucket(
    process.env.USUAL_STORAGE_BUCKET || project + ".firebasestorage.app",
  );
}
export function sourceHash(data: Args): string {
  const value = text(data, "sha256", 64, 64);
  if (!/^[a-f0-9]{64}$/.test(value))
    return fail("invalid-argument", "Invalid media checksum.");
  return value;
}
export async function prepareMedia(
  uid: string,
  uploadId: string,
  expectedHash: string,
  path: string,
  kind: string,
  scope: string,
) {
  const bucket = mediaBucket();
  const pending = bucket.file("pending/" + uid + "/" + uploadId);
  let bytes: Buffer;
  try {
    const [metadata] = await pending.getMetadata();
    if (Number(metadata.size) <= 0 || Number(metadata.size) > MAX_MEDIA_BYTES)
      return fail("invalid-argument", "Use an image of at most 4 MiB.");
    [bytes] = await pending.download();
  } catch (cause) {
    if ((cause as { code?: number }).code === 404)
      return fail("failed-precondition", "Upload the image before sending.");
    throw cause;
  }
  if (bytes.length > MAX_MEDIA_BYTES || hash(bytes) !== expectedHash)
    return fail(
      "invalid-argument",
      "The uploaded image checksum does not match.",
    );
  const png = bytes
    .subarray(0, 8)
    .equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
  const jpeg = bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255;
  if (!png && !jpeg)
    return fail("invalid-argument", "Only PNG and JPEG images are supported.");
  let normalized: Buffer;
  const mimeType = kind === "DRAWING" ? "image/png" : "image/jpeg";
  try {
    const image = sharp(bytes, {
      limitInputPixels: 16_777_216,
      failOn: "error",
    });
    const metadata = await image.metadata();
    if (
      !["png", "jpeg"].includes(metadata.format || "") ||
      !metadata.width ||
      !metadata.height
    )
      return fail("invalid-argument", "Invalid image.");
    const resized = image.rotate().resize({
      width: kind === "AVATAR" ? 512 : 1600,
      height: kind === "AVATAR" ? 512 : 1600,
      fit: "inside",
      withoutEnlargement: true,
    });
    normalized =
      kind === "DRAWING"
        ? await resized.png({ compressionLevel: 9 }).toBuffer()
        : await resized
            .flatten({ background: "#ffffff" })
            .jpeg({ quality: 85 })
            .toBuffer();
  } catch {
    return fail("invalid-argument", "The image could not be decoded safely.");
  }
  if (normalized.length > MAX_MEDIA_BYTES)
    return fail("invalid-argument", "The processed image is too large.");
  const file = bucket.file(path);
  try {
    await file.save(normalized, {
      resumable: false,
      preconditionOpts: { ifGenerationMatch: 0 },
      metadata: {
        contentType: mimeType,
        cacheControl: "no-store, max-age=0",
        metadata: { ownerUid: uid, sourceSha256: expectedHash, scope },
      },
    });
  } catch (cause) {
    if (![409, 412].includes(Number((cause as { code?: number }).code)))
      throw cause;
    const [existing] = await file.getMetadata();
    if (
      existing.metadata?.ownerUid !== uid ||
      existing.metadata?.sourceSha256 !== expectedHash ||
      existing.metadata?.scope !== scope
    )
      return fail(
        "already-exists",
        "This upload ID was used for another image.",
      );
  }
  return { path, mimeType, sha256: hash(normalized) };
}
export async function mediaResponse(path: string, thumbnail: boolean) {
  const [metadata] = await mediaBucket().file(path).getMetadata();
  if (Number(metadata.size) > MAX_MEDIA_BYTES)
    return fail("failed-precondition", "Media unavailable.");
  let [bytes] = await mediaBucket().file(path).download();
  if (bytes.length > MAX_MEDIA_BYTES)
    return fail("failed-precondition", "Media unavailable.");
  let mimeType = metadata.contentType || "image/png";
  if (thumbnail) {
    bytes = await sharp(bytes)
      .resize({
        width: 256,
        height: 256,
        fit: "inside",
        withoutEnlargement: true,
      })
      .png()
      .toBuffer();
    mimeType = "image/png";
  }
  return { mimeType, sha256: hash(bytes), base64: bytes.toString("base64") };
}
export async function removePending(
  uid: string,
  uploadId: string,
): Promise<void> {
  try {
    await mediaBucket()
      .file("pending/" + uid + "/" + uploadId)
      .delete({ ignoreNotFound: true });
  } catch {
    /* Orphan sweep retries. */
  }
}
export async function sweepOrphans(time = Date.now()): Promise<void> {
  const cutoff = time - 48 * 3_600_000;
  for (const prefix of ["pending/", "private/"]) {
    const cursorRef = db.doc("maintenanceState/" + prefix.replace("/", ""));
    const cursor = (await cursorRef.get()).data()?.pageToken;
    const [files, next] = await mediaBucket().getFiles({
      prefix,
      maxResults: 100,
      autoPaginate: false,
      ...(cursor ? { pageToken: cursor } : {}),
    });
    for (const file of files) {
      if (!(Date.parse(file.metadata.timeCreated || "") < cutoff)) continue;
      const parts = file.name.split("/");
      let orphan = prefix === "pending/";
      if (parts[1] === "couples" && parts[3] === "content")
        orphan = !(
          await db.doc("couples/" + parts[2] + "/content/" + parts[4]).get()
        ).exists;
      if (parts[1] === "users" && parts[3] === "avatar")
        orphan =
          (await db.doc("avatars/" + parts[2]).get()).data()?.mediaPath !==
          file.name;
      if (orphan) await file.delete({ ignoreNotFound: true });
    }
    await cursorRef.set({ pageToken: next?.pageToken || null });
  }
}
