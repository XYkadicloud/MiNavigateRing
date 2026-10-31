"use strict";

// Minimal Vela RPK builder for the band probe.
//
// Run from the repository root:  node scripts/build-rpk.js
// Read-only check of a real build: node scripts/build-rpk.js --check
//
// This script never writes the tracked document docs/rpk-build.md. Every run
// writes its machine-readable record to band-app/dist/rpk-build-record.json
// (dist/ is gitignored) and prints the same JSON plus the RPK facts on stdout.
//
// Verified reality of aiot-toolkit 2.0.5 (see docs/rpk-build.md):
//   * It reads the app manifest from <project>/src/manifest.json (sourceRoot
//     defaults to ./src and UxBeforeCompile.validateManifest hardcodes 'src').
//     The manifest icon (/logo.png) is likewise resolved against src, so the
//     logo must live in band-app/src/logo.png.
//   * SignUtil resolves certificates with path.join(<tempProject>, signRoot).
//     The toolkit copies the project to a sibling .temp_<dir> folder, so an
//     absolute signRoot points outside the copy, the copied sign/debug is not
//     found and the toolkit silently falls back to its bundled
//     node_modules/signature/pem certificate. signRoot must stay the relative
//     string "sign".
//   * On Windows, spawn("npm.cmd", {shell:false}) throws EINVAL on Node >= 20.12,
//     so npm is invoked through node + npm-cli.js instead of a .cmd shell shim.

const fs = require("fs");
const path = require("path");
const zlib = require("zlib");
const crypto = require("crypto");
const { spawnSync } = require("child_process");

const repoRoot = path.resolve(__dirname, "..");
const bandApp = path.join(repoRoot, "band-app");
const signingDir = path.join(repoRoot, "app", "signing");
const privSrc = path.join(signingDir, "private.pem");
const certSrc = path.join(signingDir, "certificate.pem");
const privDest = path.join(bandApp, "sign", "debug", "private.pem");
const certDest = path.join(bandApp, "sign", "debug", "certificate.pem");
const manSrc = path.join(bandApp, "src", "manifest.json");
const toolkitBin = path.join(bandApp, "node_modules", "aiot-toolkit", "lib", "bin.js");
const docPath = path.join(repoRoot, "docs", "rpk-build.md");
const recordPath = path.join(bandApp, "dist", "rpk-build-record.json");

const BUILD_ARGV = "node scripts/build-rpk.js";

let AdmZip;
try {
  AdmZip = require(path.join(bandApp, "node_modules", "adm-zip"));
} catch (err) {
  console.error("[build-rpk] cannot load band-app/node_modules/adm-zip: " + err.message);
  process.exit(1);
}

function sha256(buf) {
  return crypto.createHash("sha256").update(buf).digest("hex");
}

function crc32(buf) {
  const table = [];
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    table[n] = c >>> 0;
  }
  let crc = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    crc = table[(crc ^ buf[i]) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function pngChunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([len, body, crc]);
}

// The official manifest documentation requires a 192x192 icon; a 1x1 PNG made
// the band show a name-only app entry with a blank icon. This writes a real
// 192x192 8-bit RGBA (color type 6) PNG holding a transparent round badge: the
// disc is centred at (96,96) with a diameter of 136px, everything outside it is
// fully transparent (alpha 0) and the rim is anti-aliased over about one pixel.
// Inside the disc the background stays RGB(23,38,30) and one left-turn arrow is
// drawn in bright green RGB(0,255,153): the source arrow (tip at (28,96), head
// base x=70 y=48..144, shaft x=70..150 y=78..114) is scaled by 0.67 and
// re-centred on the disc so the glyph stays centred at (96,96).
function writeLogo(target) {
  const W = 192;
  const H = 192;
  const CX = 96;
  const CY = 96;
  const R = 68;
  const BG = [23, 38, 30, 255];
  const GREEN = [0, 255, 153, 255];
  const SCALE = 0.67;

  // The source arrow spans x=28..150, y=48..144; mapping about its own centre
  // (89,96) by SCALE and re-centring on the disc lands it at (96,96).
  const arrowX = (x) => CX + (x - 89) * SCALE;
  const arrowY = (y) => CY + (y - 96) * SCALE;
  const tipX = arrowX(28);
  const baseX = arrowX(70);
  const shaftEndX = arrowX(150);
  const baseHalf = (144 - 96) * SCALE;
  const shaftTop = arrowY(78);
  const shaftBottom = arrowY(114);

  // Every scan line starts with filter byte 0 (None), then W RGBA pixels.
  const raw = Buffer.alloc(H * (1 + W * 4));
  for (let y = 0; y < H; y++) {
    const rowStart = y * (1 + W * 4);
    raw[rowStart] = 0;
    for (let x = 0; x < W; x++) {
      const dist = Math.hypot(x - CX, y - CY);
      // Opaque inside R-0.5, transparent outside R+0.5, linear in between so
      // the rim is anti-aliased over roughly one pixel.
      let alpha;
      if (dist <= R - 0.5) {
        alpha = 255;
      } else if (dist >= R + 0.5) {
        alpha = 0;
      } else {
        alpha = Math.round(255 * (R + 0.5 - dist));
      }
      if (alpha === 0) {
        continue;
      }
      let px = BG;
      // Arrow shaft: rectangle x=70..150, y=78..114, scaled and re-centred.
      if (x >= baseX && x <= shaftEndX && y >= shaftTop && y <= shaftBottom) {
        px = GREEN;
      }
      // Arrow head: tip (28,96) widening to a base of y=48..144 at x=70.
      if (x >= tipX && x <= baseX) {
        const halfH = ((x - tipX) * baseHalf) / (baseX - tipX);
        if (Math.abs(y - CY) <= halfH) {
          px = GREEN;
        }
      }
      const off = rowStart + 1 + x * 4;
      raw[off] = px[0];
      raw[off + 1] = px[1];
      raw[off + 2] = px[2];
      raw[off + 3] = alpha;
    }
  }

  const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(W, 0);
  ihdr.writeUInt32BE(H, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  const idat = zlib.deflateSync(raw);
  const png = Buffer.concat([
    sig,
    pngChunk("IHDR", ihdr),
    pngChunk("IDAT", idat),
    pngChunk("IEND", Buffer.alloc(0))
  ]);
  fs.writeFileSync(target, png);
}

// The RPK filename is chosen explicitly, never via readdir order. aiot-toolkit
// names the file com.minavigatering.probe.debug.<versionName>.rpk in development
// mode; the exact expected name must exist exactly once.
function findRpk(dir, expectedName) {
  if (!fs.existsSync(dir)) {
    return null;
  }
  const hits = fs.readdirSync(dir).filter((f) => f === expectedName);
  if (hits.length === 0) {
    return null;
  }
  if (hits.length > 1) {
    return { ambiguous: true, name: expectedName };
  }
  return { name: hits[0], path: path.join(dir, hits[0]) };
}

// Dynamic facts live in a gitignored JSON record plus stdout only.
function writeRecord(record) {
  fs.mkdirSync(path.dirname(recordPath), { recursive: true });
  const json = JSON.stringify(record, null, 2);
  fs.writeFileSync(recordPath, json + "\n");
  return json;
}

// Failure path: never touch docs/rpk-build.md, report the record on stderr and
// leave with a non-zero exit code. aiotExitCode stays null when aiot never ran.
// versionName/versionCode are the values read from the source manifest, or null
// if the manifest could not be read yet.
function fail(message, aiotExitCode, usedProjectCert, versionName, versionCode) {
  const record = {
    result: "failure",
    argv: BUILD_ARGV,
    aiotExitCode: Number.isInteger(aiotExitCode) ? aiotExitCode : null,
    rpkPath: null,
    bytes: null,
    sha256: null,
    versionName: versionName === undefined ? null : versionName,
    versionCode: versionCode === undefined ? null : versionCode,
    usedProjectCert: !!usedProjectCert,
    message
  };
  const json = JSON.stringify(record, null, 2);
  try {
    fs.mkdirSync(path.dirname(recordPath), { recursive: true });
    fs.writeFileSync(recordPath, json + "\n");
  } catch (err) {
    // dist/ not writable: still surface the record on stderr.
  }
  console.error(json);
  process.exit(1);
}

// --check: read-only proof that a real build leaves docs/rpk-build.md alone and
// that the recorded hashes match the produced RPK. Handled before any write.
if (process.argv.includes("--check")) {
  const before = fs.readFileSync(docPath);
  const child = spawnSync(process.execPath, [__filename], {
    cwd: repoRoot,
    shell: false,
    encoding: "utf8",
    timeout: 180000
  });
  const after = fs.readFileSync(docPath);
  if (!before.equals(after)) {
    console.error("[build-rpk] --check: docs/rpk-build.md changed during the build");
    process.exit(1);
  }
  if (child.status !== 0) {
    console.error("[build-rpk] --check: child build exited with status " + child.status);
    console.error(child.stdout || "");
    console.error(child.stderr || "");
    process.exit(1);
  }
  let record;
  try {
    record = JSON.parse(fs.readFileSync(recordPath, "utf8"));
  } catch (err) {
    console.error("[build-rpk] --check: cannot read " + recordPath + ": " + err.message);
    process.exit(1);
  }
  if (record.result !== "success") {
    console.error('[build-rpk] --check: record result is "' + record.result + '", expected "success"');
    process.exit(1);
  }
  // The record must describe the current source manifest, not a stale build.
  let man;
  try {
    man = JSON.parse(fs.readFileSync(manSrc, "utf8"));
  } catch (err) {
    console.error("[build-rpk] --check: cannot read " + manSrc + ": " + err.message);
    process.exit(1);
  }
  if (record.versionName !== man.versionName || record.versionCode !== man.versionCode) {
    console.error(
      "[build-rpk] --check: record version " +
      record.versionName + "/" + record.versionCode +
      " does not match the source manifest " + man.versionName + "/" + man.versionCode
    );
    process.exit(1);
  }
  const expectedName = man.package + ".debug." + man.versionName + ".rpk";
  if (path.basename(record.rpkPath) !== expectedName) {
    console.error("[build-rpk] --check: rpkPath " + record.rpkPath + " is not " + expectedName);
    process.exit(1);
  }
  if (!record.diagnosticRpkPath || !fs.existsSync(path.resolve(repoRoot, record.diagnosticRpkPath))) {
    console.error("[build-rpk] --check: diagnosticRpkPath is missing or not on disk");
    process.exit(1);
  }
  const diagnosticBytes = fs.readFileSync(path.resolve(repoRoot, record.diagnosticRpkPath));
  if (sha256(diagnosticBytes) !== record.sha256) {
    console.error(
      "[build-rpk] --check: diagnosticRpkPath sha256 " + sha256(diagnosticBytes) +
      " != record.sha256 " + record.sha256
    );
    process.exit(1);
  }
  let bytes;
  try {
    bytes = fs.readFileSync(path.resolve(repoRoot, record.rpkPath));
  } catch (err) {
    console.error("[build-rpk] --check: cannot read recorded rpkPath: " + err.message);
    process.exit(1);
  }
  if (bytes.length !== record.bytes || sha256(bytes) !== record.sha256) {
    console.error(
      "[build-rpk] --check: RPK facts differ from the record " +
      `(bytes ${bytes.length} vs ${record.bytes}, sha256 ${sha256(bytes)} vs ${record.sha256})`
    );
    process.exit(1);
  }
  console.log("check-ok");
  process.exit(0);
}

// 1. Require the existing signing material (never generate a new key).
if (!fs.existsSync(privSrc) || !fs.existsSync(certSrc)) {
  fail(
    "app/signing/private.pem or app/signing/certificate.pem is missing; " +
    "per constraints no new key is generated and no RPK is built.",
    null,
    false
  );
}

// 2. The toolkit validates <project>/src/manifest.json before anything else.
//    Without it the build cannot succeed, so refuse to call aiot at all. The
//    version identity read here is the single source of truth for this build;
//    nothing is hardcoded.
if (!fs.existsSync(manSrc)) {
  fail(
    "band-app/src/manifest.json is missing; aiot-toolkit 2.0.5 reads the " +
    "manifest from <project>/src, so aiot was not called and no rpk was written.",
    null,
    false
  );
}
let manifest;
try {
  manifest = JSON.parse(fs.readFileSync(manSrc, "utf8"));
} catch (err) {
  fail(
    "band-app/src/manifest.json is not valid JSON (" + err.message + "); " +
    "aiot was not called and no rpk was written.",
    null,
    false
  );
}
const manifestVersionName = manifest.versionName;
const manifestVersionCode = manifest.versionCode;
// The package name drives both the RPK filename and the pairing Mi Fitness does between
// the phone app and the band app, so an empty one must fail here rather than produce
// ".debug.<version>.rpk" and a bundle Mi Fitness will not associate with anything.
if (typeof manifest.package !== "string" || manifest.package.length === 0) {
  fail(
    "band-app/src/manifest.json package is not a non-empty string (got " +
    JSON.stringify(manifest.package) + "); aiot was not called and no rpk was written.",
    null,
    false
  );
}
if (typeof manifestVersionName !== "string" || manifestVersionName.length === 0) {
  fail(
    "band-app/src/manifest.json versionName is not a non-empty string (got " +
    JSON.stringify(manifestVersionName) + "); aiot was not called and no rpk was written.",
    null,
    false
  );
}
if (!Number.isInteger(manifestVersionCode) || manifestVersionCode <= 0) {
  fail(
    "band-app/src/manifest.json versionCode is not a positive integer (got " +
    JSON.stringify(manifestVersionCode) + "); aiot was not called and no rpk was written.",
    null,
    false
  );
}
const expectedRpkName =
  manifest.package + ".debug." + manifestVersionName + ".rpk";

// 3. Copy the existing pems and write the gitignored logo next to the manifest
//    so that `icon: "/logo.png"` resolves against sourceRoot `src`.
fs.mkdirSync(path.dirname(privDest), { recursive: true });
fs.copyFileSync(privSrc, privDest);
fs.copyFileSync(certSrc, certDest);
writeLogo(path.join(bandApp, "src", "logo.png"));

// 4. The copied certificate must be byte-identical to the project one.
if (!fs.readFileSync(certDest).equals(fs.readFileSync(certSrc))) {
  fail(
    "band-app/sign/debug/certificate.pem differs byte-wise from " +
    "app/signing/certificate.pem, so use of the project certificate could " +
    "not be proven; the build was not continued.",
    null,
    false
  );
}

// 5. Install dependencies only when the toolkit itself is absent. npm is called
//    through node + npm-cli.js: spawning npm.cmd with shell:false throws EINVAL
//    on modern Node, and a shell shim is forbidden.
if (!fs.existsSync(toolkitBin)) {
  const candidates = [
    path.join(path.dirname(process.execPath), "node_modules", "npm", "bin", "npm-cli.js"),
    path.join(path.dirname(process.execPath), "..", "lib", "node_modules", "npm", "bin", "npm-cli.js")
  ];
  const npmCli = candidates.find((p) => fs.existsSync(p));
  if (!npmCli) {
    fail(
      "band-app/node_modules/aiot-toolkit/lib/bin.js is absent and no " +
      "npm-cli.js was found under " + candidates.join(" ; ") +
      "; npm.cmd is not used and no RPK was built.",
      null,
      false
    );
  }
  const install = spawnSync(process.execPath, [npmCli, "install", "--no-audit", "--no-fund"], {
    cwd: bandApp,
    shell: false,
    encoding: "utf8",
    timeout: 180000
  });
  if (install.error || install.status !== 0) {
    fail(
      "dependency install via process.execPath + npm-cli.js failed (" +
      (install.error ? "spawn error " + install.error.code : "exit code " + install.status) +
      "); no RPK was built.",
      null,
      false
    );
  }
}

// 6. Build. The ticket wants `aiot build`; the local CLI is invoked directly so
//    that no shell wrapper is used.
const build = spawnSync(process.execPath, [toolkitBin, "build"], {
  cwd: bandApp,
  shell: false,
  encoding: "utf8",
  timeout: 180000
});
const buildOut = (build.stdout || "") + (build.stderr || "");
const usedOurKey = /sign[\\/]debug[\\/]private\.pem/i.test(buildOut) &&
  !/signature[\\/]pem[\\/]private\.pem/i.test(buildOut);
const aiotExitCode = build.error ? null : build.status;

// 7. Never claim an RPK when the build or the certificate check failed.
if (build.error || build.status !== 0 || !usedOurKey) {
  fail(
    "no usable RPK: " +
    (build.error ? "aiot spawn failed (" + build.error.code + ")" : "aiot exit code " + build.status) +
    (usedOurKey ? "" : "; the run did not use app/signing sign/debug/private.pem") +
    ". Last aiot output: " + buildOut.trim().split("\n").slice(-30).join(" / "),
    aiotExitCode,
    usedOurKey,
    manifestVersionName,
    manifestVersionCode
  );
}

// Select exactly the RPK the toolkit names com.minavigatering.probe.debug.<v>.rpk
// and never the first .rpk readdir happens to return (dist also holds diagnostic
// copies and payloads for other versions).
const found = findRpk(path.join(bandApp, "dist"), expectedRpkName);
if (!found) {
  fail(
    "the build exited 0 but no rpk named " + expectedRpkName + " was found in " +
    "band-app/dist; no RPK is claimed.",
    build.status,
    usedOurKey,
    manifestVersionName,
    manifestVersionCode
  );
}
if (found.ambiguous) {
  fail(
    "more than one rpk named " + expectedRpkName + " exists in band-app/dist; " +
    "the build product is ambiguous and no RPK is claimed.",
    build.status,
    usedOurKey,
    manifestVersionName,
    manifestVersionCode
  );
}
const rpk = found.path;
if (path.basename(rpk).indexOf("diagnostic-icon-") === 0) {
  fail(
    "the selected rpk is a diagnostic-icon copy, not the build product; no RPK is claimed.",
    build.status,
    usedOurKey,
    manifestVersionName,
    manifestVersionCode
  );
}

// 8. The distributed copy is only the same bytes; refuse to claim it when the
//    file that aiot wrote has disappeared.
if (!fs.existsSync(rpk)) {
  fail(
    "the selected rpk " + rpk + " is no longer on disk; no RPK is claimed.",
    build.status,
    usedOurKey,
    manifestVersionName,
    manifestVersionCode
  );
}
const bytes = fs.readFileSync(rpk);

// 9. Inspect the packaged artifact with adm-zip: the internal manifest must
//    carry the source version identity and icon, and logo.png must still be a
//    192x192 RGBA PNG. Any mismatch means the artifact is not this build.
let zip;
try {
  zip = new AdmZip(rpk);
} catch (err) {
  fail(
    "cannot open " + rpk + " as a zip: " + err.message + "; no RPK is claimed.",
    build.status,
    usedOurKey,
    manifestVersionName,
    manifestVersionCode
  );
}
const manEntry = zip.getEntry("manifest.json");
let packagedManifest;
try {
  packagedManifest = JSON.parse(manEntry.getData().toString("utf8"));
} catch (err) {
  fail(
    "the packaged manifest.json is missing or not valid JSON: " + err.message +
    "; no RPK is claimed.",
    build.status,
    usedOurKey,
    manifestVersionName,
    manifestVersionCode
  );
}
if (packagedManifest.versionName !== manifestVersionName ||
    packagedManifest.versionCode !== manifestVersionCode) {
  fail(
    "the packaged manifest version " + packagedManifest.versionName + "/" +
    packagedManifest.versionCode + " differs from the source manifest " +
    manifestVersionName + "/" + manifestVersionCode + "; no RPK is claimed.",
    build.status,
    usedOurKey,
    manifestVersionName,
    manifestVersionCode
  );
}
if (packagedManifest.icon !== "/logo.png") {
  fail(
    'the packaged manifest icon is ' + JSON.stringify(packagedManifest.icon) +
    ', expected "/logo.png"; no RPK is claimed.',
    build.status,
    usedOurKey,
    manifestVersionName,
    manifestVersionCode
  );
}
const logoEntry = zip.getEntry("logo.png");
const logo = logoEntry ? logoEntry.getData() : null;
const PNG_SIG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const logoWidth = logo && logo.length >= 24 && logo.slice(0, 8).equals(PNG_SIG)
  ? logo.readUInt32BE(16)
  : null;
const logoHeight = logo && logo.length >= 24 && logo.slice(0, 8).equals(PNG_SIG)
  ? logo.readUInt32BE(20)
  : null;
if (logoWidth !== 192 || logoHeight !== 192) {
  fail(
    "the packaged logo.png is " + logoWidth + "x" + logoHeight + ", expected 192x192; " +
    "no RPK is claimed.",
    build.status,
    usedOurKey,
    manifestVersionName,
    manifestVersionCode
  );
}
const digest = sha256(bytes);

// 10. Keep a byte-identical diagnostic copy whose name carries the versionCode
//     and the leading 8 hex digits of the hash, so L3 can verify the build it
//     hands to the band without trusting a moving filename.
const diagnosticName =
  "diagnostic-icon-vc" + manifestVersionCode + "-" + digest.slice(0, 8) + ".rpk";
const diagnosticRpkPath = path.join(bandApp, "dist", diagnosticName);
fs.copyFileSync(rpk, diagnosticRpkPath);

const json = writeRecord({
  result: "success",
  argv: BUILD_ARGV,
  aiotExitCode: build.status,
  rpkPath: path.relative(repoRoot, rpk),
  diagnosticRpkPath: path.relative(repoRoot, diagnosticRpkPath),
  bytes: bytes.length,
  sha256: digest,
  versionName: manifestVersionName,
  versionCode: manifestVersionCode,
  packagedIcon: packagedManifest.icon,
  logoWidth,
  logoHeight,
  usedProjectCert: usedOurKey,
  message: "aiot-toolkit 2.0.5 build succeeded with the app/signing sign/debug certificate."
});
console.log(json);
console.log("[build-rpk] rpk " + rpk);
console.log("[build-rpk] diagnostic-rpk " + diagnosticRpkPath);
console.log("[build-rpk] bytes " + bytes.length);
console.log("[build-rpk] sha256 " + digest);
