"use strict";
// Dual-bundle regression proof for the shared session and for the official page
// data Object rule (https://iot.mi.com/vela/quickapp/zh/guide/framework/script/page-data.html,
// `data: Object`). The shipped 0.1.4-refresh bundle declared `data()` as a
// function, so a rule-abiding host never initialised the page: init=NaN and
// ready/show/tick=undefined. build/device-page-init/vc5-negative-0.1.4-refresh.rpk
// is a read-only byte copy of that bundle and is replayed here under the same
// Object-only rule; the freshly built 0.1.5-init bundle must initialise every
// field. The defect only exists in
// the compiled product: app.js and pages/index/index.js each carry their own copy
// of session.js and each webpack runtime keeps its own module cache, so the app
// can ack a frame while the page still renders its initial "连接中". Both chunks
// keep their own `var __webpack_module_cache__ = {}` and this script never rewrites
// or merges that cache. It loads the real chunks one realm per chunk and binds the
// page to the app export through this.$app.$def. Exits 0 after printing
// dual-bundle-share-ok, else 1.
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");
const nodeVm = require("node:vm");
const repoRoot = path.resolve(__dirname, "..", "..");
const recordPath = path.join(repoRoot, "band-app", "dist", "rpk-build-record.json");
// 基线 RPK 是 MiRing-V0-probe 工作树的构建产物。工作树已统一收进主仓的
// .worktrees/ 目录，因此不写死绝对路径：从当前工作树逐级向上定位 .worktrees；
// 需要指向别处时用 MIRING_BASELINE_RPK 覆盖。
const BASELINE_RPK_REL = path.join(".worktrees", "missions",
  "MiRing-V0-probe-20261012", "band-app", "dist",
  "com.mapring.probe.debug.0.1.2-diag.rpk");
const BASELINE_RPK = process.env.MIRING_BASELINE_RPK || findUp(BASELINE_RPK_REL);

// 从 repoRoot 起逐级向上查找相对路径；找不到时仍返回一个具体候选，
// 让 checkBaseline 的缺失提示带上实际尝试过的位置。
function findUp(rel) {
  let dir = repoRoot;
  for (let i = 0; i < 6; i += 1) {
    const candidate = path.join(dir, rel);
    if (fs.existsSync(candidate)) return candidate;
    const up = path.dirname(dir);
    if (up === dir) break;
    dir = up;
  }
  return path.join(repoRoot, rel);
}
const NEW_RPK_NAME = "com.mapring.probe.debug.0.1.5-init.rpk";
// Read-only byte copy of the shipped 0.1.4-refresh bundle (and its build
// record), used as the negative fixture under the official Object-only data
// rule. The copy never changes; its sha256 is checked on every run.
const VC5_NEGATIVE_RPK = path.join(repoRoot, "build", "device-page-init", "vc5-negative-0.1.4-refresh.rpk");
const VC5_NEGATIVE_SHA256 = "a14572bf6ff43922346a84f77be0b5b27fd688e4d0a9fe3dc6036003c7dddc22";
// The page fields whose initialisation is asserted for both bundles. tickTimer
// is deliberately absent: it is a plain instance property, not page data.
const PAGE_DATA_FIELDS = [
  "title", "distanceText", "hasDistance", "roadName", "exitLabel",
  "diagReady", "diagStatus", "inboundSummary", "linkEvent",
  "lifeInit", "lifeReady", "lifeShow", "lifeTick", "lifeText"
];
const APP_GRADLE = path.join(repoRoot, "app", "build.gradle.kts");
const ZIP = require(path.join(repoRoot, "band-app", "node_modules", "adm-zip"));

function fail(message) {
  console.error(message);
  process.exit(1);
}
function sha256(buf) {
  return require("node:crypto").createHash("sha256").update(buf).digest("hex");
}
function readEntry(rpkPath, entryName) {
  const entry = new ZIP(rpkPath).getEntry(entryName);
  if (!entry) fail("entry " + entryName + " is missing from " + rpkPath);
  return entry.getData().toString("utf8");
}

// A fresh realm per chunk: the bundles never share a realm, only host values.
function makeContext(exp, requireImpl) {
  const ctx = {
    console, Date, Math, JSON, Set, Map, Object, Array, Number, String, Boolean,
    Error, TypeError, Promise, setTimeout, setInterval, clearTimeout, clearInterval,
    $app_exports$: exp, $app_require$: requireImpl
  };
  ctx.global = ctx;
  ctx.globalThis = ctx;
  return nodeVm.createContext(ctx);
}
// The host wraps the chunk in parentheses and calls it with (global, globalThis,
// window, exports, evaluate); dropping `export default` keeps the same function.
function loadChunk(code, ctx, exp) {
  const stripped = code.replace(/^\s*export\s+default\s+/, "");
  const fn = nodeVm.runInContext("(" + stripped + "\n)", ctx, { filename: "chunk.js" });
  return fn(ctx, ctx, ctx, exp, (c) => eval(c));
}

// Official host binding for a page: the data Object's fields become getter/
// setter accessors over $data, methods are bound to the instance, and
// this.$app = { $def } is set. Per
// https://iot.mi.com/vela/quickapp/zh/guide/framework/script/page-data.html
// `data` is an Object, never a function: this host only ever reads the object
// and never calls it. A function-shaped data (the shipped defect) therefore
// leaves the page with no initial values at all, exactly like on the band.
function makePageInstance(def, appExport) {
  const target = {};
  const rawData = def.data;
  const objectShaped = rawData !== null && typeof rawData === "object" && !Array.isArray(rawData);
  const data = objectShaped ? rawData : {};
  const $data = Object.assign({}, data);
  Object.keys(data).forEach((key) => {
    Object.defineProperty(target, key, {
      get() { return $data[key]; },
      set(v) { $data[key] = v; },
      enumerable: true,
      configurable: true
    });
  });
  Object.keys(def).forEach((key) => {
    if (typeof def[key] === "function" && key !== "data") target[key] = def[key].bind(target);
  });
  target.$data = $data;
  target.$app = { $def: appExport };
  target.$dataShape = objectShaped ? "object" : typeof rawData;
  return target;
}

// The page chunk reads global.setInterval/clearInterval when it is evaluated, so
// wrap them before loading it. 回调由 onInit 注册，onShow 只恢复：the wrapper records
// whichever callback onInit/onShow arms and schedules no real timer, so nothing
// can fire during the assertions.
function capturePageTimers(ctx) {
  const timers = { registered: [], nextId: 1 };
  ctx.setInterval = function (cb) {
    const entry = { id: timers.nextId++, cb };
    timers.registered.push(entry);
    return entry.id;
  };
  ctx.clearInterval = function (id) {
    const at = timers.registered.findIndex((entry) => entry.id === id);
    if (at !== -1) timers.registered.splice(at, 1);
  };
  return timers;
}

// Run the registered interval callback: the only visible-page refresh path.
// Calling page.tick() directly would prove nothing about the installed timer.
function fireRegisteredInterval(timers) {
  assert.strictEqual(timers.registered.length, 1,
    "exactly one interval must be registered, got " + timers.registered.length);
  timers.registered[0].cb();
}

// The one frame shape both this test and the product use.
function frameFor(id, distanceM) {
  return {
    v: 1, kind: "nav", id, action: "left", actionId: "act-base", mode: "cycle",
    distanceM, roadName: "合成夹具", heartbeat: false, navStatus: "active", link: "normal"
  };
}
// Build the shared interconnect stub inside the chunk's own realm so the function
// identity is native to that realm. getReadyState/diagnosis answer asynchronously:
// their success payloads are queued and never delivered during onCreate.
function installInterconnect(ctx, relm) {
  ctx.__relm = relm;
  relm.diagnosisQueue = [];
  nodeVm.runInContext(
    "__relm.connect = {" +
      " onmessage: null, onopen: null, onclose: null, onerror: null, sent: []," +
      " send(arg) { this.sent.push(arg); }," +
      " getReadyState(o) { __relm.diagnosisQueue.push(function () { o.success({ status: 1 }); }); }," +
      " diagnosis(o) { __relm.diagnosisQueue.push(function () { o.success({ status: 0 }); }); }" +
      " };", ctx);
}
// Deliver exactly what the host queued, in enqueue order: the only place the
// diagnosis success callbacks ever run, so 未探测 only leaves via explicit flush.
function flushDiagnosisQueue(relm) {
  const queued = relm.diagnosisQueue.splice(0);
  queued.forEach((deliver) => deliver());
  return queued.length;
}
function interconnectRequire(relm, throwing) {
  return function (name) {
    if (throwing) throw new Error("host module unavailable: " + name);
    if (name.indexOf("interconnect") !== -1) return { instance() { return relm.connect; } };
    if (name.indexOf("vibrator") !== -1) return { vibrate() {} };
    throw new Error("unexpected module: " + name);
  };
}

function pushFrame(ctx, relm, frameObj) {
  ctx.__frame = JSON.stringify(frameObj);
  nodeVm.runInContext("__relm.connect.onmessage({ data: __frame })", ctx);
}
function sentRefIds(ctx, relm) {
  return nodeVm.runInContext("JSON.stringify(__relm.connect.sent)", ctx);
}
function onmessageType(ctx) {
  return nodeVm.runInContext("typeof __relm.connect.onmessage", ctx);
}
function loadApp(rpkPath, throwing) {
  const relm = {};
  const exp = {};
  const ctx = makeContext(exp, interconnectRequire(relm, throwing));
  installInterconnect(ctx, relm);
  loadChunk(readEntry(rpkPath, "app.js"), ctx, exp);
  return { relm, ctx, appExport: exp.default || exp };
}
function loadPage(rpkPath, appExport, wrapTimers) {
  const exp = {};
  const ctx = makeContext(exp, interconnectRequire({}, false));
  const timers = wrapTimers ? capturePageTimers(ctx) : null;
  loadChunk(readEntry(rpkPath, "pages/index/index.js"), ctx, exp);
  exp.entry(exp);
  return { page: makePageInstance(exp.default, appExport), timers };
}

// --- baseline: the shipped defect, read-only ---
function checkBaseline() {
  if (!fs.existsSync(BASELINE_RPK)) fail("read-only baseline rpk is missing: " + BASELINE_RPK);
  const appCode = readEntry(BASELINE_RPK, "app.js");
  const pageCode = readEntry(BASELINE_RPK, "pages/index/index.js");
  // The cache is never rewritten: both chunks keep their own.
  assert.ok(appCode.indexOf("var __webpack_module_cache__ = {}") !== -1,
    "baseline app.js must still contain its own webpack module cache");
  assert.ok(pageCode.indexOf("var __webpack_module_cache__ = {}") !== -1,
    "baseline page.js must still contain its own webpack module cache");

  const app = loadApp(BASELINE_RPK, false);
  app.appExport.onCreate();
  assert.strictEqual(onmessageType(app.ctx), "function",
    "baseline onCreate must register connect.onmessage");
  pushFrame(app.ctx, app.relm, frameFor("msg-300", 300));
  const sent = sentRefIds(app.ctx, app.relm);
  assert.ok(sent.indexOf('"refId":"msg-300"') !== -1, "baseline app must ack msg-300, got " + sent);

  // The page bundle has its own session copy, so it never sees that frame. The
  // same onInit-only condition as the fixed build: onShow is never called, so
  // the only refresh path available is the callback onInit registered, if any.
  const { page, timers } = loadPage(BASELINE_RPK, app.appExport, true);
  page.onInit();
  if (timers.registered.length > 0) {
    timers.registered.forEach((entry) => entry.cb());
  }
  // page.tick() is deliberately not called: the negative case must not be
  // rescued by a direct method call.
  assert.notStrictEqual(page.title, "左转", "baseline onInit-only must never show a turn");
  assert.notStrictEqual(page.distanceText, "300米", "baseline onInit-only must never show 300米");
  console.log("baseline-oninit-only-failed");
}

// --- fixed build ---
function checkRecordAndRpk() {
  if (!fs.existsSync(recordPath)) {
    fail("band-app/dist/rpk-build-record.json is missing; run node scripts/build-rpk.js first");
  }
  const record = JSON.parse(fs.readFileSync(recordPath, "utf8"));
  assert.strictEqual(record.result, "success", "build record result must be success");
  assert.strictEqual(record.versionName, "0.1.5-init", "build record versionName");
  assert.strictEqual(record.versionCode, 6, "build record versionCode");
  const rpkPath = path.resolve(repoRoot, record.rpkPath);
  assert.strictEqual(path.basename(rpkPath), NEW_RPK_NAME, "build record rpk basename");
  assert.strictEqual(sha256(fs.readFileSync(rpkPath)), record.sha256, "rpk sha256 must match the record");
  return rpkPath;
}

function checkFixedArtifact(rpkPath) {
  const appCode = readEntry(rpkPath, "app.js");
  const pageCode = readEntry(rpkPath, "pages/index/index.js");
  assert.ok(appCode.indexOf("navSession") !== -1, "app.js must publish navSession");
  assert.ok(appCode.indexOf("@app-module/system.interconnect") !== -1,
    "app.js must still register the interconnect listeners");
  assert.ok(pageCode.indexOf("navSession") !== -1, "page.js must read navSession");
  assert.ok(pageCode.indexOf("$app") !== -1, "page.js must use this.$app");
  assert.strictEqual(pageCode.indexOf("@app-module/system.interconnect"), -1,
    "page.js must not import interconnect");
  // The official rule, checked on the real compiled chunk: data is an object
  // literal, never a method, and tickTimer stays out of it.
  assert.ok(pageCode.indexOf("data: {") !== -1 || pageCode.indexOf("data:{") !== -1,
    "compiled page must declare data as an object literal");
  assert.strictEqual(pageCode.indexOf("data () {"), -1,
    "compiled page must not contain a data() method");
  assert.strictEqual(/data\s*\(\s*\)\s*\{/.test(pageCode), false,
    "compiled page must not contain a data() function shape");
  assert.strictEqual(pageCode.indexOf("tickTimer:"), -1,
    "tickTimer must stay a plain instance property, not a data field");
  const packagedName = JSON.parse(readEntry(rpkPath, "manifest.json")).package;
  assert.strictEqual(packagedName, "com.mapring.probe", "packaged manifest package");
  const packagedManifest = JSON.parse(readEntry(rpkPath, "manifest.json"));
  assert.strictEqual(packagedManifest.versionName, "0.1.5-init", "packaged manifest versionName");
  assert.strictEqual(packagedManifest.versionCode, 6, "packaged manifest versionCode");
  const gradle = fs.readFileSync(APP_GRADLE, "utf8").match(/applicationId\s*=\s*"([^"]+)"/);
  assert.ok(gradle, "could not read applicationId from " + APP_GRADLE);
  // The two ids used to be identical. They are now deliberately different: the Android
  // applicationId had to change so Mi Fitness stops rejecting the app's signing
  // fingerprint (it records the fingerprint per applicationId on first grant and never
  // rewrites it), while the RPK package must stay put or the band would show a second,
  // orphaned app entry. What still has to hold is that neither is empty and that the
  // RPK package is the expected legacy value.
  assert.ok(gradle[1] && gradle[1].indexOf(".") !== -1, "Android applicationId must look like a package");
  assert.strictEqual(gradle[1], "com.mapring.probe", "Android applicationId");
  return { packagedName, packagedManifest };
}

// Lifecycle counters are rendered inside lifeText; read one field back from it
// instead of trusting the separate numeric fields. Returns null when the field
// is absent, which is exactly the negative result for the shipped bundle.
function lifeValue(page, field) {
  const hit = new RegExp(field + "=(\\d+)").exec(page.lifeText);
  return hit ? Number(hit[1]) : null;
}

// Full page-data initialisation report for one bundle: which fields a
// rule-abiding host (Object-only data) actually initialises.
function pageDataInitReport(page) {
  const missing = [];
  PAGE_DATA_FIELDS.forEach((field) => {
    if (page.$dataShape !== "object" || page.$data[field] === undefined) missing.push(field);
  });
  return {
    dataShape: page.$dataShape,
    initialisedFields: PAGE_DATA_FIELDS.filter((f) => page.$dataShape === "object" && page.$data[f] !== undefined),
    missingFields: missing,
    lifeText: page.lifeText,
    lifeInit: lifeValue(page, "init"),
    lifeReady: lifeValue(page, "ready"),
    lifeShow: lifeValue(page, "show"),
    lifeTick: lifeValue(page, "tick")
  };
}

// --- negative fixture: the shipped 0.1.4-refresh bundle, official rule ---
function checkVc5NegativeFixture() {
  if (!fs.existsSync(VC5_NEGATIVE_RPK)) {
    fail("read-only vc5 negative fixture copy is missing: " + VC5_NEGATIVE_RPK);
  }
  assert.strictEqual(sha256(fs.readFileSync(VC5_NEGATIVE_RPK)), VC5_NEGATIVE_SHA256,
    "the vc5 negative fixture must stay the byte copy of the shipped 0.1.4-refresh rpk");

  const app = loadApp(VC5_NEGATIVE_RPK, false);
  app.appExport.onCreate();
  pushFrame(app.ctx, app.relm, frameFor("msg-300", 300));
  const sent = sentRefIds(app.ctx, app.relm);
  assert.ok(sent.indexOf('"refId":"msg-300"') !== -1,
    "vc5-negative app must still ack msg-300 on the host side, got " + sent);

  const { page, timers } = loadPage(VC5_NEGATIVE_RPK, app.appExport, true);
  assert.strictEqual(page.$dataShape, "function",
    "the shipped bundle must declare data as a function, that is the defect");
  // Right after construction, before any lifecycle runs: an Object-only host
  // initialises nothing at all from a function-shaped data. Every declared
  // page field is missing from $data.
  assert.strictEqual(Object.keys(page.$data).length, 0,
    "Object-only host must leave the shipped page's $data empty, got " +
      Object.keys(page.$data).join(","));
  PAGE_DATA_FIELDS.forEach((field) => {
    assert.strictEqual(page.$data[field], undefined,
      "shipped page field " + field + " must be uninitialised, got " + String(page.$data[field]));
  });
  assert.strictEqual(page.hasDistance, undefined, "shipped hasDistance must be uninitialised");
  assert.strictEqual(page.diagReady, undefined, "shipped diagReady must be uninitialised");

  // onInit then runs: the lifecycle counters can only be NaN/undefined because
  // bumpLife adds to fields that were never initialised.
  page.onInit();
  const report = pageDataInitReport(page);
  assert.strictEqual(report.lifeInit, null, "lifeInit must be missing (not 0): got " + page.lifeText);
  assert.strictEqual(report.lifeReady, null, "lifeReady must be missing (not 0): got " + page.lifeText);
  assert.strictEqual(report.lifeShow, null, "lifeShow must be missing (not 0): got " + page.lifeText);
  assert.strictEqual(report.lifeTick, null, "lifeTick must be missing (not 0): got " + page.lifeText);
  assert.ok(/init=NaN/.test(page.lifeText), "shipped lifeText must read init=NaN, got " + page.lifeText);
  assert.ok(/ready=undefined/.test(page.lifeText), "shipped ready must read undefined, got " + page.lifeText);
  assert.ok(/show=undefined/.test(page.lifeText), "shipped show must read undefined, got " + page.lifeText);
  assert.ok(/tick=undefined/.test(page.lifeText), "shipped tick must read undefined, got " + page.lifeText);
  // The shipped page does re-read the shared session on its first tick, so its
  // title later becomes 左转: that is the session readback, not a page data
  // initialisation. The counters stay NaN/undefined for the whole run, which is
  // exactly what the band user saw.
  const vc5LifeTextAfterInterval = (() => {
    if (timers.registered.length > 0) timers.registered[0].cb();
    return page.lifeText;
  })();
  assert.ok(/init=NaN/.test(vc5LifeTextAfterInterval),
    "the shipped counters must stay NaN after the interval, got " + vc5LifeTextAfterInterval);
  assert.strictEqual(lifeValue(page, "tick"), null,
    "shipped tick must never become a number, got " + page.lifeText);
  console.log("vc5-negative-page-init dataShape=function initialisedFields=0 missingFields=" +
    report.missingFields.length + " lifeText=" + page.lifeText);
  return report;
}

// Every page field must be initialised after onInit on the new bundle, and the
// four counters must be finite numbers with init >= 1.
function checkFixedPageInit(rpkPath) {
  const app = loadApp(rpkPath, false);
  app.appExport.onCreate();
  const { page, timers } = loadPage(rpkPath, app.appExport, true);
  assert.strictEqual(page.$dataShape, "object",
    "the fixed bundle must declare data as an Object, got " + page.$dataShape);
  page.onInit();
  const report = pageDataInitReport(page);
  assert.strictEqual(report.missingFields.length, 0,
    "the new bundle must initialise every page field, missing: " + report.missingFields.join(","));
  assert.strictEqual(page.title, "连接中", "title must be the declared initial value");
  assert.strictEqual(page.diagReady, "未探测", "diagReady must be the declared initial value");
  assert.strictEqual(page.diagStatus, "未探测", "diagStatus must be the declared initial value");
  assert.strictEqual(page.inboundSummary, "0帧", "inboundSummary must be the declared initial value");
  assert.strictEqual(page.linkEvent, "无", "linkEvent must be the declared initial value");
  assert.strictEqual(page.hasDistance, false, "hasDistance must be the declared initial value");
  assert.strictEqual(page.distanceText, "", "distanceText must be the declared initial value");
  assert.strictEqual(page.roadName, "", "roadName must be the declared initial value");
  assert.strictEqual(page.exitLabel, "", "exitLabel must be the declared initial value");
  assert.ok(Number.isFinite(page.lifeInit) && page.lifeInit >= 1,
    "lifeInit must be a finite number >= 1, got " + page.lifeInit);
  ["lifeReady", "lifeShow", "lifeTick"].forEach((f) => {
    assert.ok(Number.isFinite(page[f]), f + " must be a finite number, got " + page[f]);
  });
  // The interval callback must make lifeTick grow without any manual tick call.
  assert.strictEqual(timers.registered.length, 1, "onInit must register exactly one interval");
  fireRegisteredInterval(timers);
  fireRegisteredInterval(timers);
  assert.strictEqual(page.lifeTick, 2, "the registered callback must grow lifeTick, got " + page.lifeTick);
  assert.strictEqual(page.lifeInit, 1, "the interval callback must not touch lifeInit, got " + page.lifeInit);
  assert.strictEqual(lifeValue(page, "tick"), 2, "lifeText must agree with lifeTick, got " + page.lifeText);
  console.log("fixed-page-init dataShape=object initialisedFields=" +
    report.initialisedFields.length + " lifeText=" + page.lifeText);
  return report;
}

function checkFixedSuccessPath(rpkPath) {
  const app = loadApp(rpkPath, false);
  assert.strictEqual(app.appExport.navInit, "未初始化", "navInit must start uninitialised");
  assert.strictEqual(app.appExport.navSession, null, "navSession must start null");
  app.appExport.onCreate();
  assert.strictEqual(app.appExport.navInit, "ok", "navInit must be ok after a successful onCreate");
  assert.notStrictEqual(app.appExport.navSession, null, "navSession must be published");
  assert.strictEqual(onmessageType(app.ctx), "function", "onmessage must be registered");
  // The diagnosis answers must not have been delivered from inside onCreate.
  assert.strictEqual(app.relm.diagnosisQueue.length, 2,
    "onCreate must have queued getReadyState+diagnosis without delivering them, got " +
      app.relm.diagnosisQueue.length);

  const { page, timers } = loadPage(rpkPath, app.appExport, true);
  // The defect this ticket closes: only onInit runs. onShow is never called and
  // onReady is not called either, so the only refresh path is the timer onInit
  // armed itself.
  page.onInit();
  assert.strictEqual(timers.registered.length, 1,
    "onInit must register exactly one interval, got " + timers.registered.length);
  assert.strictEqual(page.lifeText, "init=1 ready=0 show=0 tick=0",
    "onInit must count itself only, got " + page.lifeText);
  assert.strictEqual(page.title, "连接中", "no frame yet: title stays 连接中");
  assert.strictEqual(page.diagReady, "未探测", "diagnosis not flushed yet: ready stays 未探测");
  assert.notStrictEqual(page.diagReady, "异常初始化",
    "a wired host must not look abnormal: 未探测 is the initial value, not a failure");
  assert.strictEqual(page.hasDistance, false, "no frame yet: no distance");

  // onReady may arrive afterwards and must not arm a second timer.
  page.onReady();
  assert.strictEqual(timers.registered.length, 1,
    "onReady must not register a second interval, got " + timers.registered.length);
  assert.strictEqual(lifeValue(page, "ready"), 1, "onReady must count once");
  assert.strictEqual(lifeValue(page, "show"), 0, "onReady must not count a show");
  // onReady only counts: the page is not repainted by it.
  assert.strictEqual(page.diagReady, "未探测", "onReady alone must not flush the diagnosis");

  // Only the host's queued success answers change the ready/diagnosis lines, and
  // only the interval callback armed by onInit repaints the page. page.tick() is
  // never called here: a direct method call would prove nothing about the timer.
  assert.strictEqual(flushDiagnosisQueue(app.relm), 2, "both queued callbacks must flush");
  assert.strictEqual(page.diagReady, "未探测", "nothing repaints until the interval fires");
  fireRegisteredInterval(timers);
  assert.strictEqual(page.diagReady, "1已连接", "after flush, ready must read 1已连接");
  assert.strictEqual(page.diagStatus, "0对端已连接", "after flush, diagnosis must read 0对端已连接");
  assert.strictEqual(lifeValue(page, "tick"), 1, "the interval callback must add one tick, got " + page.lifeText);

  pushFrame(app.ctx, app.relm, frameFor("msg-300", 300));
  fireRegisteredInterval(timers);
  assert.strictEqual(page.title, "左转", "fixed page must see the turn after sharing");
  assert.strictEqual(page.distanceText, "300米", "fixed page must show 300米");
  assert.strictEqual(page.hasDistance, true, "fixed page must have a distance");

  pushFrame(app.ctx, app.relm, frameFor("msg-200", 200));
  fireRegisteredInterval(timers);
  assert.strictEqual(page.distanceText, "200米", "fixed page must follow 300米 -> 200米");
  assert.strictEqual(page.title, "左转", "same action keeps the left turn");

  let sent = sentRefIds(app.ctx, app.relm);
  assert.ok(sent.indexOf('"refId":"msg-300"') !== -1, "must ack msg-300, got " + sent);
  assert.ok(sent.indexOf('"refId":"msg-200"') !== -1, "must ack msg-200, got " + sent);

  // Leaving the page stops the local timer only; the newest state stays in the
  // shared session. While hidden nothing may repaint the page.
  page.onHide();
  assert.strictEqual(timers.registered.length, 0, "onHide must clear the page interval");
  pushFrame(app.ctx, app.relm, frameFor("msg-100", 100));
  assert.strictEqual(page.distanceText, "200米", "a hidden page must not repaint on a new frame");
  assert.strictEqual(page.title, "左转", "a hidden page keeps its last turn");

  // Coming back runs onShow, which ticks immediately and re-arms the timer.
  page.onShow();
  assert.strictEqual(page.distanceText, "100米", "onShow must show the newest frame received while hidden");
  assert.strictEqual(page.title, "左转", "the turn is still the left turn after returning");
  assert.strictEqual(timers.registered.length, 1, "onShow must re-arm the interval");
  assert.ok(lifeValue(page, "show") >= 1, "onShow must count a show, got " + page.lifeText);
  sent = sentRefIds(app.ctx, app.relm);
  assert.ok(sent.indexOf('"refId":"msg-100"') !== -1, "must ack msg-100, got " + sent);
}

function checkFixedAbnormalPath(rpkPath) {
  const app = loadApp(rpkPath, true);
  assert.doesNotThrow(() => app.appExport.onCreate(), "onCreate must never throw");
  assert.strictEqual(app.appExport.navInit, "异常初始化", "failed onCreate must report 异常初始化");
  assert.strictEqual(app.appExport.navSession, null, "failed onCreate must not publish a session");

  const { page, timers } = loadPage(rpkPath, app.appExport, true);
  page.onInit();
  assert.strictEqual(page.title, "异常初始化", "page must show 异常初始化");
  assert.strictEqual(page.diagReady, "异常初始化", "diagReady must show 异常初始化");
  assert.strictEqual(page.diagStatus, "异常初始化", "diagStatus must show 异常初始化");
  assert.strictEqual(page.inboundSummary, "异常初始化", "inboundSummary must show 异常初始化");
  assert.strictEqual(page.linkEvent, "异常初始化", "linkEvent must show 异常初始化");
  assert.strictEqual(page.hasDistance, false, "no distance on the abnormal path");
  assert.notStrictEqual(page.diagReady, "未探测", "abnormal init must not look like 未探测");

  // The timer keeps running on the abnormal path too, and every later refresh
  // must stay on 异常初始化 instead of degrading to 未探测 or 连接中.
  assert.strictEqual(timers.registered.length, 1, "onInit arms the interval on the abnormal path too");
  fireRegisteredInterval(timers);
  assert.strictEqual(page.title, "异常初始化", "the interval callback must keep 异常初始化");
  assert.strictEqual(page.diagReady, "异常初始化", "the interval callback must keep diagReady abnormal");
  assert.strictEqual(page.diagStatus, "异常初始化", "the interval callback must keep diagStatus abnormal");
  assert.strictEqual(page.inboundSummary, "异常初始化", "the interval callback must keep the inbound line abnormal");
  assert.strictEqual(page.linkEvent, "异常初始化", "the interval callback must keep the link line abnormal");
  assert.notStrictEqual(page.diagReady, "未探测", "abnormal init must never become 未探测 after a refresh");
  assert.notStrictEqual(page.title, "连接中", "abnormal init must never become 连接中 after a refresh");
}

// --- run ---
function runExistingRegression() {
  checkBaseline();
  console.log("baseline-defect-reproduced cacheRewritten=false");
  checkVc5NegativeFixture();
  const rpkPath = checkRecordAndRpk();
  checkFixedArtifact(rpkPath);
  checkFixedPageInit(rpkPath);
  checkFixedSuccessPath(rpkPath);
  checkFixedAbnormalPath(rpkPath);
  console.log("cacheRewritten=false");
  console.log("background-device=unverified");
  console.log("icon=unverified");
  console.log("dual-bundle-share-ok");
  return rpkPath;
}

// --- page-init validation entry point (--page-init-validation) ---
const PAGE_INIT_OUT = path.join(repoRoot, "build", "device-page-init");
const NODE_TEST_TARGETS = ["band-app/test/startup.test.js", "band-app/test/session.test.js"];
const STATUS_URL = "http://127.0.0.1:8092/status";
const FRAME_URL = "http://127.0.0.1:8092/frame.png";

// Targeted regression run: only startup+session, and the child exit code is
// propagated verbatim. No shell, so the exit code cannot be swallowed.
function runTargetedNodeTests() {
  const child = require("node:child_process").spawnSync(
    process.execPath,
    ["--test", ...NODE_TEST_TARGETS],
    { cwd: repoRoot, shell: false, encoding: "utf8" }
  );
  const tail = (s) => String(s || "").split(/\r?\n/).slice(-25).join("\n");
  console.log("targeted-node-test-argv=" + JSON.stringify([process.execPath, "--test", ...NODE_TEST_TARGETS]));
  console.log("targeted-node-test-exit=" + child.status);
  console.log(tail(child.stdout));
  if (child.status !== 0) {
    console.error(tail(child.stderr));
    fail("targeted node --test exited with " + child.status);
  }
  return child.status;
}

// Read-only side evidence from the shared 8092 server. It is explicitly an OLD
// server instance: the reported version is recorded verbatim so nobody mistakes
// its screenshot for the new bundle.
function readSideEvidence() {
  return new Promise((resolve) => {
    const http = require("node:http");
    const get = (url, isBinary) => new Promise((done) => {
      let settled = false;
      const finish = (r) => { if (!settled) { settled = true; done(r); } };
      const req = http.get(url, (res) => {
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () => finish({ ok: true, status: res.statusCode, body: Buffer.concat(chunks) }));
      });
      req.on("error", (e) => finish({ ok: false, error: String(e && e.message || e) }));
      req.setTimeout(4000, () => { try { req.destroy(new Error("timeout")); } catch (e) { /* ignore */ } });
    });
    (async () => {
      const status = await get(STATUS_URL, false);
      const frame = await get(FRAME_URL, true);
      const result = { statusUrl: STATUS_URL, frameUrl: FRAME_URL, status: null, framePng: null };
      if (status.ok) {
        try {
          result.status = JSON.parse(status.body.toString("utf8"));
        } catch (e) {
          result.status = { parseError: String(e && e.message || e) };
        }
      } else {
        result.status = { error: status.error };
      }
      if (frame.ok) {
        const p = path.join(PAGE_INIT_OUT, "side-8092-frame.png");
        fs.writeFileSync(p, frame.body);
        result.framePng = { path: p, bytes: frame.body.length, sha256: sha256(frame.body) };
      } else {
        result.framePng = { error: frame.error };
      }
      result.servedVersion = result.status && result.status.version ? result.status.version : null;
      result.note = "the 8092 server is a pre-existing instance; the version above is the OLD bundle it serves, never the newly built one";
      resolve(result);
    })();
  });
}

// Load the real RPK into the existing read-only native runtime layer WITHOUT
// buildBundle: each chunk runs as its own IIFE and keeps its own module cache
// (the same shape as MiRing-Sim-Compatibility scripts/compare-rpk-cache-modes.js
// loadLocalMode). Then the runtime's own native launchPage is called: the page
// data initialisation and the frame come from the runtime, never from us.
function runNativeSimulation(rpkPath) {
  const RUNTIME_DIR = "C:/program1/bes-qemu/miband9/rpk_runtime";
  const PAGE_PATH = "pages/index";
  const { QuickAppRuntime } = require(path.join(RUNTIME_DIR, "quickapp_vm.js"));
  const { RpkPackage } = require(path.join(RUNTIME_DIR, "rpk_loader.js"));
  const { renderQuickAppFrame } = require(path.join(RUNTIME_DIR, "rpk_renderer.js"));
  const nodeVm = require("node:vm");

  const pkg = new RpkPackage(rpkPath);
  const rt = new QuickAppRuntime({ width: 192, height: 490,
    storageFile: path.join(PAGE_INIT_OUT, "native-storage.json") });

  // loadLocalMode shape: raw cache stays local to each chunk.
  const ctx = rt.buildSandbox();
  const loaded = [];
  const specs = [
    { name: "app.js", isApp: true },
    { name: "pages/index/index.js", isApp: false }
  ];
  for (const spec of specs) {
    const raw = pkg.files.get(spec.name);
    if (!raw) throw new Error("RPK chunk missing: " + spec.name);
    let code = raw.toString("utf8");
    if (code.startsWith("export default function")) code = code.replace(/^export\s+default\s+/, "");
    assert.strictEqual(code.indexOf("var __webpack_module_cache__ = {}") !== -1, true,
      spec.name + " must keep its own webpack module cache");
    const script = new nodeVm.Script("(\n" + code + "\n)", { filename: spec.name });
    const fn = script.runInContext(ctx);
    const exp = {};
    fn(ctx, ctx, ctx, exp, function (c) { return eval(c); });
    if (spec.isApp) {
      const app = exp.default || exp;
      ctx.$app_instance$ = app;
      if (typeof app.onCreate === "function") app.onCreate.call(app);
    } else if (typeof exp["entry"] === "function") {
      const pageExp = {};
      exp["entry"](pageExp);
      ctx.$register_page$(PAGE_PATH, pageExp.default || pageExp);
    } else {
      ctx.$register_page$(PAGE_PATH, exp.default || exp);
    }
    loaded.push({ file: spec.name, cacheRewritten: false });
  }

  const outcome = {
    evidenceClass: "native-runtime-local-cache-no-buildBundle",
    buildBundleUsed: false,
    cacheRewritten: false,
    runtimeDir: RUNTIME_DIR,
    rpkPath: rpkPath,
    rpkSha256: sha256(fs.readFileSync(rpkPath)),
    loaded: loaded
  };

  // The sandbox has no $app at all: the page's official this.$app.$def binding
  // cannot resolve, which is exactly the abnormal-initialisation path.
  outcome.sandboxHasApp = Object.prototype.hasOwnProperty.call(ctx, "$app");
  outcome.sandboxAppDefBound = false;

  const page = rt.launchPage(PAGE_PATH);
  outcome.launchErrors = rt.errors.map((e) => ({ name: e.name, message: e.message, phase: e.phase }));

  const summarize = (d) => ({
    title: d.title, distanceText: d.distanceText, hasDistance: d.hasDistance,
    roadName: d.roadName, exitLabel: d.exitLabel, diagReady: d.diagReady,
    diagStatus: d.diagStatus, inboundSummary: d.inboundSummary, linkEvent: d.linkEvent,
    lifeInit: d.lifeInit, lifeReady: d.lifeReady, lifeShow: d.lifeShow, lifeTick: d.lifeTick,
    lifeText: d.lifeText
  });
  outcome.pageDataShape = page.data === null ? "null" : typeof page.data;
  outcome.pageDataAfterLaunch = summarize(page.$data || {});

  // Wait for the page's own real 1000 ms timer: nothing is ticked by hand.
  const beforeTick = summarize(page.$data || {});
  return new Promise((resolve) => {
    setTimeout(() => {
      const afterTick = summarize(page.$data || {});
      outcome.timerWaitMs = 2600;
      outcome.pageDataBeforeTimer = beforeTick;
      outcome.pageDataAfterTimer = afterTick;
      outcome.timerAdvancedLifeTick =
        Number.isFinite(afterTick.lifeTick) && Number.isFinite(beforeTick.lifeTick) &&
        afterTick.lifeTick > beforeTick.lifeTick;

      // Synthetic frames only: a host-side injection, never a device delivery.
      const conn = rt.interconnectInstances[0];
      outcome.interconnectInstanceAvailable = Boolean(conn && typeof conn.onmessage === "function");
      outcome.interconnectTx = [];
      rt.on("comm:interconnect_tx", (rec) => outcome.interconnectTx.push(rec.data));
      const inject = (id, distanceM) => {
        const frame = {
          v: 1, kind: "nav", id: id, action: "left", actionId: "act-native", mode: "cycle",
          distanceM: distanceM, roadName: "合成夹具", heartbeat: false,
          navStatus: "active", link: "normal"
        };
        try {
          conn.onmessage({ data: JSON.stringify(frame) });
        } catch (e) {
          outcome.injectError = String(e && e.message || e);
        }
        return frame;
      };
      if (outcome.interconnectInstanceAvailable) {
        inject("native-msg-300", 300);
      }
      setTimeout(() => {
        if (outcome.interconnectInstanceAvailable) inject("native-msg-200", 200);
        setTimeout(() => {
          outcome.pageDataAfterSynthetic300to200 = summarize(page.$data || {});
          outcome.commLog = rt.commLog;
          outcome.vibrateLog = rt.vibrateLog;
          outcome.logs = rt.logs;
          outcome.errors = rt.errors.map((e) => ({ name: e.name, message: e.message, phase: e.phase }));
          outcome.renderTree = rt.renderTree;
          const png = renderQuickAppFrame(rt.renderTree).toPngBuffer();
          const framePath = path.join(PAGE_INIT_OUT, "native-frame.png");
          fs.writeFileSync(framePath, png);
          outcome.nativeFrame = { path: framePath, bytes: png.length, sha256: sha256(png) };
          resolve(outcome);
        }, 1200);
      }, 1200);
    }, 2600);
  });
}

// The runtime has no $app binding at all, so the native launch can only show
// the abnormal-initialisation path and the distance lines can never move. That
// is a capability gap, not a pass: exit 0 marks a complete verification run.
function judgeNativeSimulation(native) {
  const reasons = [];
  if (!native.sandboxHasApp) {
    reasons.push("native sandbox has no $app, so this.$app.$def cannot resolve and the shared session is unreachable");
  }
  reasons.push("the native page layout is hand-written in quickapp_vm.js and no faithful template renderer runs, so template-bound fields cannot be observed");
  if (!native.interconnectInstanceAvailable) {
    reasons.push("no interconnect instance with onmessage, so a frame cannot be injected");
  }
  return { status: "SIM_BLOCKED", reasons: reasons };
}

async function runPageInitValidation(rpkPath) {
  fs.mkdirSync(PAGE_INIT_OUT, { recursive: true });
  const exitCode = runTargetedNodeTests();
  const native = await runNativeSimulation(rpkPath);
  const verdict = judgeNativeSimulation(native);
  const side = await readSideEvidence();
  const bundle = {
    entry: "--page-init-validation",
    targetedNodeTest: { argv: [process.execPath, "--test", ...NODE_TEST_TARGETS], exitCode: exitCode },
    nativeSimulator: native,
    nativeVerdict: verdict,
    side8092: side
  };
  const outPath = path.join(PAGE_INIT_OUT, "page-init-validation.json");
  fs.writeFileSync(outPath, JSON.stringify(bundle, null, 2) + "\n");
  console.log("page-init-validation-record=" + outPath);
  console.log("native-page-data=" + JSON.stringify(native.pageDataAfterLaunch));
  console.log("native-page-data-after-timer=" + JSON.stringify(native.pageDataAfterTimer));
  console.log("native-page-data-after-synthetic-300to200=" + JSON.stringify(native.pageDataAfterSynthetic300to200));
  console.log("native-frame=" + JSON.stringify(native.nativeFrame));
  console.log("side-8092-version=" + JSON.stringify(side.servedVersion));
  if (verdict.status === "SIM_BLOCKED") {
    console.log("SIM_BLOCKED " + verdict.reasons.join(" | "));
  }
  console.log("page-init-validation-done");
  // The native runtime arms a real 1000 ms interval; the run is finished here.
  process.exit(0);
}

try {
  const rpkPath = runExistingRegression();
  if (process.argv.includes("--page-init-validation")) {
    runPageInitValidation(rpkPath).catch((err) => {
      fail("page-init-validation failed: " + (err && err.stack ? err.stack : err));
    });
  }
} catch (err) {
  fail("dual-bundle-share failed: " + (err && err.stack ? err.stack : err));
}
