/**
 * Uploads the signed release APK to the public Vercel Blob store and prints its direct download URL.
 * Usage (from server/): npx tsx scripts/upload-apk.ts ../android/app/build/outputs/apk/release/app-release.apk [version]
 */
import { readFileSync, statSync } from 'node:fs';
import { config } from 'dotenv';
import { put } from '@vercel/blob';

config({ path: '.env.local' });

const [file, version = '1.0.0'] = process.argv.slice(2);
if (!file) throw new Error('Pass the APK path');
const token = process.env.BLOB_READ_WRITE_TOKEN;
if (!token) throw new Error('BLOB_READ_WRITE_TOKEN is not set');

const size = statSync(file).size;
const blob = await put(`localcart/releases/LocalCart-${version}.apk`, readFileSync(file), {
  access: 'public',
  contentType: 'application/vnd.android.package-archive',
  addRandomSuffix: false,
  allowOverwrite: true,
  token,
  multipart: size > 8 * 1024 * 1024,
});
console.log(`Uploaded ${(size / 1024 / 1024).toFixed(1)} MB`);
console.log(blob.url);
