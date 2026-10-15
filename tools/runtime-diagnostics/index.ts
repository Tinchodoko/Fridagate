import Java from "frida-java-bridge";

declare const console: {
  log(...data: unknown[]): void;
  error(...data: unknown[]): void;
};

const PREFIX = "[FRIDAGATE-TEST]";
function log(message: string): void { console.log(PREFIX + " " + message); }
function reportError(area: string, error: unknown): void {
  log("ERROR " + area + ": " + String(error));
}

log("========== RUNTIME DIAGNOSTICS START ==========");
try {
  log("TEST 1 PASS: JavaScript is executing inside the target process.");
  log("TEST 2: Frida runtime = " + (typeof Frida !== "undefined" && Frida.version ? Frida.version : "not exposed"));
  log("TEST 3 PASS: PID = " + Process.id);
  log("Platform = " + Process.platform);
  log("Architecture = " + Process.arch);
  log("Pointer size = " + Process.pointerSize + " bytes");
} catch (error) { reportError("process information", error); }

try {
  const modules = Process.enumerateModules();
  log("TEST 4 PASS: modules enumerated = " + modules.length);
  for (const name of ["libc.so", "libart.so", "libandroid.so", "libil2cpp.so", "libunity.so", "libflutter.so"]) {
    log("Module " + name + ": " + (modules.some(m => m.name === name) ? "PRESENT" : "NOT DETECTED"));
  }
  log("First loaded modules:");
  modules.slice(0, 12).forEach(m => log("  " + m.name + " | base=" + m.base + " | size=" + m.size));
} catch (error) { reportError("native module enumeration", error); }

try {
  const threads = Process.enumerateThreads();
  log("TEST 5 PASS: threads enumerated = " + threads.length);
  threads.slice(0, 10).forEach(t => log("  Thread ID=" + t.id + " name=" + (t.name || "(unnamed)")));
} catch (error) { reportError("thread enumeration", error); }

try {
  log("TEST 6: frida-java-bridge bundle loaded.");
  log("Java.available = " + Java.available);
  if (!Java.available) {
    log("TEST 6 WARN: bridge is bundled, but Java VM is not available in this process yet.");
  } else {
    Java.perform(() => {
      log("TEST 6 PASS: Java.perform() callback executed.");
      log("Android version = " + Java.androidVersion);
      try {
        Java.use("android.app.Activity");
        log("TEST 6 PASS: android.app.Activity is accessible.");
      } catch (error) {
        reportError("Java.use(android.app.Activity)", error);
      }
      try {
        const Version = Java.use("android.os.Build$VERSION");
        log("Android release = " + Version.RELEASE.value + "; SDK_INT = " + Version.SDK_INT.value);
      } catch (error) { reportError("Android Build.VERSION", error); }
      log("TEST 6 DONE: Java bridge validation finished.");
    });
  }
} catch (error) {
  reportError("Java bridge initialization", error);
}

try {
  const names = Process.enumerateModules().map(m => m.name.toLowerCase());
  log("TEST 7: native framework inspection complete.");
  log("Unity detected = " + names.some(n => n.includes("unity") || n === "libil2cpp.so"));
  log("Flutter detected = " + names.some(n => n.includes("flutter")));
} catch (error) { reportError("framework detection", error); }

log("========== RUNTIME DIAGNOSTICS FINISHED ==========");
