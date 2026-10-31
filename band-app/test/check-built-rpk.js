"use strict";

// Plain node script (not a test framework) that inspects a built RPK.
//
// Run from the repository root:  node band-app/test/check-built-rpk.js
//
// It proves the shipped artifact is the version-differential diagnostic build:
//   * logo.png is a real 192x192 image, not a 1x1 placeholder;
//   * app.js contains the onCreate script, not just the empty statement "";.
//   * the recorded version identity matches the source manifest and the file is
//     com.mapring.probe.debug.0.1.1.rpk, not the shipped 0.1.0 payload;
//   * the diagnostic copy exists and its SHA256 equals the record.
// Any failure exits 1; success prints check-built-rpk-ok and exits 0. No hash
// is written into any tracked file.

const fs = require("fs");
const path = require("path");
const zlib = require("zlib");
const crypto = require("crypto");

const repoRoot = path.resolve(__dirname, "..", "..");
const recordPath = path.join(repoRoot, "band-app", "dist", "rpk-build-record.json");
const manifestPath = path.join(repoRoot, "band-app", "src", "manifest.json");
const shippedOldRpk = path.join(
  repoRoot,
  "band-app",
  "dist",
  "com.mapring.probe.debug.0.1.0.rpk"
);
const BG = [23, 38, 30];

function sha256(buf) {
  return crypto.createHash("sha256").update(buf).digest("hex");
}

function fail(message) {
  console.error("check-built-rpk: " + message);
  process.exit(1);
}

let AdmZip;
try {
  AdmZip = require(path.join(repoRoot, "band-app", "node_modules", "adm-zip"));
} catch (err) {
  fail("cannot load adm-zip: " + err.message);
}

if (!fs.existsSync(recordPath)) {
  fail("missing build record " + recordPath);
}
let record;
try {
  record = JSON.parse(fs.readFileSync(recordPath, "utf8"));
} catch (err) {
  fail("cannot parse build record: " + err.message);
}
if (record.result !== "success") {
  fail('record result is "' + record.result + '", expected "success"');
}
if (!record.rpkPath) {
  fail("record has no rpkPath");
}

// --- source manifest: the version-differential identity ---------------------
let manifest;
try {
  manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
} catch (err) {
  fail("cannot read source manifest " + manifestPath + ": " + err.message);
}
if (manifest.versionCode !== 2) {
  fail("source manifest versionCode is " + manifest.versionCode + ", expected 2");
}
if (manifest.versionName !== "0.1.1") {
  fail('source manifest versionName is "' + manifest.versionName + '", expected "0.1.1"');
}
if (manifest.icon !== "/logo.png") {
  fail('source manifest icon is "' + manifest.icon + '", expected "/logo.png"');
}

// --- record identity must equal the source manifest, not 0.1.0 --------------
if (record.versionName !== manifest.versionName || record.versionCode !== manifest.versionCode) {
  fail(
    "record version " + record.versionName + "/" + record.versionCode +
    " does not match the source manifest " + manifest.versionName + "/" + manifest.versionCode
  );
}
if (record.versionName === "0.1.0") {
  fail('record versionName is still the stale "0.1.0"');
}

// --- the recorded rpkPath must be exactly this build product ----------------
if (!/^com\.minavigatering\.probe\.debug\.0\.1\.1\.rpk$/.test(path.basename(record.rpkPath))) {
  fail("record rpkPath file name is " + path.basename(record.rpkPath) +
    ", expected com.mapring.probe.debug.0.1.1.rpk");
}
if (/^com\.minavigatering\.probe\.debug\.0\.1\.0\.rpk$/.test(path.basename(record.rpkPath))) {
  fail("record rpkPath points at the shipped 0.1.0 payload, not this build");
}
if (path.basename(record.rpkPath).indexOf("diagnostic-icon-") === 0) {
  fail("record rpkPath points at a diagnostic-icon copy, not the build product");
}

const rpkPath = path.resolve(repoRoot, record.rpkPath);
if (!fs.existsSync(rpkPath)) {
  fail("recorded rpk is missing: " + rpkPath);
}

let zip;
try {
  zip = new AdmZip(rpkPath);
} catch (err) {
  fail("cannot open rpk as zip: " + err.message);
}

function readEntry(name) {
  const entry = zip.getEntry(name);
  if (!entry) {
    fail("rpk has no " + name);
  }
  return entry.getData();
}

// --- packaged manifest: version identity and icon match the source manifest --
let packagedManifest;
try {
  packagedManifest = JSON.parse(readEntry("manifest.json").toString("utf8"));
} catch (err) {
  fail("packaged manifest.json is not valid JSON: " + err.message);
}
if (packagedManifest.versionName !== manifest.versionName ||
    packagedManifest.versionCode !== manifest.versionCode) {
  fail(
    "packaged manifest version " + packagedManifest.versionName + "/" +
    packagedManifest.versionCode + " does not match the source manifest " +
    manifest.versionName + "/" + manifest.versionCode
  );
}
if (packagedManifest.icon !== "/logo.png") {
  fail('packaged manifest icon is "' + packagedManifest.icon + '", expected "/logo.png"');
}

// --- logo.png: real 192x192, not a 1x1 or a flat fill -----------------------
const logo = readEntry("logo.png");
if (logo.length <= 70) {
  fail("logo.png is only " + logo.length + " bytes; expected > 70");
}
const PNG_SIG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
if (!logo.slice(0, 8).equals(PNG_SIG)) {
  fail("logo.png is not a PNG");
}
// First chunk must be IHDR: [len(4)][type(4)][data(13)][crc(4)].
const ihdrType = logo.slice(12, 16).toString("ascii");
if (ihdrType !== "IHDR") {
  fail("logo.png first chunk is " + ihdrType + ", expected IHDR");
}
const width = logo.readUInt32BE(16);
const height = logo.readUInt32BE(20);
const bitDepth = logo[24];
const colorType = logo[25];
if (width !== 192 || height !== 192) {
  fail("logo.png is " + width + "x" + height + ", expected 192x192");
}
if (bitDepth !== 8 || colorType !== 6) {
  fail("logo.png is bitDepth " + bitDepth + " colorType " + colorType + ", expected 8/6");
}

// Walk the chunks, concatenate IDAT, inflate, and count non-background pixels.
let offset = 8;
let idat = Buffer.alloc(0);
let sawIend = false;
while (offset < logo.length) {
  const len = logo.readUInt32BE(offset);
  const type = logo.slice(offset + 4, offset + 8).toString("ascii");
  const data = logo.slice(offset + 8, offset + 8 + len);
  if (type === "IDAT") {
    idat = Buffer.concat([idat, data]);
  } else if (type === "IEND") {
    sawIend = true;
    break;
  }
  offset += 12 + len;
}
if (!sawIend) {
  fail("logo.png has no IEND chunk");
}
let raw;
try {
  raw = zlib.inflateSync(idat);
} catch (err) {
  fail("cannot inflate logo IDAT: " + err.message);
}
const stride = 1 + width * 4;
if (raw.length !== height * stride) {
  fail("logo raw size " + raw.length + " != " + height * stride);
}
let nonBg = 0;
for (let y = 0; y < height; y++) {
  const rowStart = y * stride;
  if (raw[rowStart] !== 0) {
    fail("logo scan line " + y + " has filter " + raw[rowStart] + ", expected 0");
  }
  for (let x = 0; x < width; x++) {
    const off = rowStart + 1 + x * 4;
    if (raw[off] !== BG[0] || raw[off + 1] !== BG[1] || raw[off + 2] !== BG[2]) {
      nonBg++;
    }
  }
}
if (nonBg < 500) {
  fail("logo has only " + nonBg + " non-background pixels; expected >= 500");
}

// --- app.js: real onCreate script, not an empty-script bundle ---------------
const appJs = readEntry("app.js").toString("utf8");
if (!appJs.includes("onCreate")) {
  fail("app.js does not contain onCreate");
}
if (!appJs.includes("system.interconnect")) {
  fail("app.js does not contain system.interconnect");
}
// A script that compiled to nothing looks like:
//   var $app_script$ = function __scriptModule__(module, exports, $app_require$1) {
//     "use strict";
//     "";
//   };
// i.e. the only statement in the module body is the empty literal. Require the
// module body to carry real work by checking the actual script function text.
const scriptFnMatch = appJs.match(/\$app_script\$\s*=\s*function[^{]*\{([\s\S]*?)\n\s*\};/);
if (scriptFnMatch) {
  const body = scriptFnMatch[1];
  const stripped = body.replace(/"use strict";/, "");
  if (/^\s*""\s*;?\s*$/.test(stripped)) {
    fail('app.js $app_script$ body is only the empty statement ""; (app.ux likely lost its <script> tag)');
  }
}

// --- pages/index/index.js: real onInit -------------------------------------
const indexJs = readEntry("pages/index/index.js").toString("utf8");
if (!indexJs.includes("onInit")) {
  fail("pages/index/index.js does not contain onInit");
}

// --- diagnostic copy: present and byte-identical to the record --------------
if (!record.diagnosticRpkPath) {
  fail("record has no diagnosticRpkPath");
}
const diagnosticPath = path.resolve(repoRoot, record.diagnosticRpkPath);
if (!fs.existsSync(diagnosticPath)) {
  fail("diagnosticRpkPath is missing on disk: " + diagnosticPath);
}
const diagnosticDigest = sha256(fs.readFileSync(diagnosticPath));
if (diagnosticDigest !== record.sha256) {
  fail("diagnosticRpkPath sha256 " + diagnosticDigest + " != record.sha256 " + record.sha256);
}

// --- the shipped 0.1.0 payload is a historical artifact: assert its facts if
//     it is still present, but never fail because it was removed -------------
if (fs.existsSync(shippedOldRpk)) {
  let oldZip;
  try {
    oldZip = new AdmZip(shippedOldRpk);
  } catch (err) {
    fail("cannot open shipped 0.1.0 rpk as zip: " + err.message);
  }
  const oldManifestEntry = oldZip.getEntry("manifest.json");
  let oldManifest;
  try {
    oldManifest = JSON.parse(oldManifestEntry.getData().toString("utf8"));
  } catch (err) {
    fail("shipped 0.1.0 manifest.json is not valid JSON: " + err.message);
  }
  if (oldManifest.versionCode !== 1) {
    fail("shipped 0.1.0 internal versionCode is " + oldManifest.versionCode + ", expected 1");
  }
  const oldLogoEntry = oldZip.getEntry("logo.png");
  if (!oldLogoEntry) {
    fail("shipped 0.1.0 rpk has no logo.png");
  }
  const oldLogo = oldLogoEntry.getData();
  const oldWidth = oldLogo.readUInt32BE(16);
  const oldHeight = oldLogo.readUInt32BE(20);
  if (oldWidth !== 192 || oldHeight !== 192) {
    fail("shipped 0.1.0 logo.png is " + oldWidth + "x" + oldHeight + ", expected 192x192");
  }
}

console.log("check-built-rpk-ok");
process.exit(0);
