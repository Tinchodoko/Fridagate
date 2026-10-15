import Java from "frida-java-bridge";

declare const console: { log(...data: unknown[]): void; };

const PREFIX = "[FRIDAGATE-TEST]";
function log(msg: string) { console.log(PREFIX + " " + msg); }
function reportError(area: string, e: unknown) { log("ERROR " + area + ": " + String(e)); }

log("========== RUNTIME DIAGNOSTICS START ==========");

try {
  log("TEST 1 PASS: JavaScript is executing inside the target process.");
  log("TEST 2: Frida runtime = " + (typeof Frida !== "undefined" ? Frida.version : "not exposed"));
  log("TEST 3 PASS: PID = " + Process.id);
  log("Platform = " + Process.platform + " | Arch = " + Process.arch + " | Pointer = " + Process.pointerSize + " bytes");
} catch (e) { reportError("process info", e); }

try {
  const modules = Process.enumerateModules();
  log("TEST 4 PASS: modules enumerated = " + modules.length);
  for (const n of ["libc.so","libart.so","libandroid.so","libil2cpp.so","libunity.so","libflutter.so"])
    log("Module " + n + ": " + (modules.some(m => m.name === n) ? "PRESENT" : "NOT DETECTED"));
  log("First loaded modules:");
  modules.slice(0,12).forEach(m => log("  " + m.name + " | base=" + m.base + " | size=" + m.size));
} catch (e) { reportError("module enumeration", e); }

try {
  const threads = Process.enumerateThreads();
  log("TEST 5 PASS: threads enumerated = " + threads.length);
  threads.slice(0,10).forEach(t => log("  Thread ID=" + t.id + " name=" + (t.name || "(unnamed)")));
} catch (e) { reportError("thread enumeration", e); }

// TEST 6 — chequeo SINCRÓNICO (garantizado en el log)
try {
  log("TEST 6: frida-java-bridge bundle loaded.");
  log("Java.available = " + Java.available);
  if (Java.available) log("TEST 6 PASS: Java bridge ready (sync).");
  else log("TEST 6 WARN: bridge bundled but VM not available yet.");
} catch (e) { reportError("Java bridge sync check", e); }

// TEST 7 — ANTES del async, garantizado en el log
try {
  const names = Process.enumerateModules().map(m => m.name.toLowerCase());
  log("TEST 7: Unity detected = " + names.some(n => n.includes("unity") || n === "libil2cpp.so"));
  log("TEST 7: Flutter detected = " + names.some(n => n.includes("flutter")));
} catch (e) { reportError("framework detection", e); }

// FINISHED sincrónico — siempre aparece
log("========== RUNTIME DIAGNOSTICS FINISHED ==========");

// Java.perform — async best-effort, si corre es bonus
try {
  if (Java.available) {
    Java.perform(() => {
      log("TEST 6 ASYNC PASS: Java.perform() executed.");
      log("Android version = " + Java.androidVersion);
      try { Java.use("android.app.Activity"); log("TEST 6 ASYNC: Activity accessible."); }
      catch (e) { reportError("Java.use Activity", e); }
      try {
        const V = Java.use("android.os.Build$VERSION");
        log("Release = " + V.RELEASE.value + " SDK=" + V.SDK_INT.value);
      } catch (e) { reportError("Build.VERSION", e); }
      log("TEST 6 ASYNC DONE.");
    });
  }
} catch (e) { reportError("Java.perform", e); }
