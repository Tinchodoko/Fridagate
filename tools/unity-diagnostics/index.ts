import Java from "frida-java-bridge";
import "frida-il2cpp-bridge";

// Frida's TypeScript compiler omits lib.dom.d.ts, but its runtime provides console.
declare const console: {
    log(...data: unknown[]): void;
    warn(...data: unknown[]): void;
    error(...data: unknown[]): void;
};

const TAG = "[FG-UNITY-IL2CPP]";
const startedAt = Date.now();
let il2cppStarted = false;
let javaReported = false;
let lastModuleState = "";

function log(level: string, message: string): void {
    const elapsed = ((Date.now() - startedAt) / 1000).toFixed(3).padStart(8, " ");
    console.log(`${TAG} [${elapsed}] ${level} ${message}`);
}

log("BOOT", "Bundle de diagnóstico iniciado (FridaGate; bridges incluidos).");

try {
    log("INFO", `Runtime: ${Script.runtime}; arquitectura: ${Process.arch}; plataforma: ${Process.platform}`);
} catch (error) {
    log("WARN", `No se pudo consultar el runtime: ${error}`);
}

function checkIl2CppModule(): void {
    let moduleFound = false;
    try {
        moduleFound = Process.findModuleByName("libil2cpp.so") !== null;
    } catch (error) {
        log("WARN", `Error consultando módulos: ${error}`);
    }

    if (moduleFound) {
        if (lastModuleState !== "found") {
            lastModuleState = "found";
            log("INFO", "libil2cpp.so detectada.");
        }
        if (!il2cppStarted) {
            il2cppStarted = true;
            try {
                Il2Cpp.perform(() => {
                    try {
                        log("OK", `Puente IL2CPP inicializado. Unity: ${Il2Cpp.unityVersion}`);
                        const assemblies = Il2Cpp.domain.assemblies;
                        log("INFO", `Ensamblados administrados detectados: ${assemblies.length}`);
                        assemblies.slice(0, 40).forEach((assembly) => log("ASSEMBLY", assembly.name));
                        if (assemblies.length > 40) {
                            log("INFO", `Se omitieron ${assemblies.length - 40} nombres adicionales para mantener el registro legible.`);
                        }
                    } catch (error) {
                        log("ERROR", `IL2CPP respondió, pero falló la enumeración de ensamblados: ${error}`);
                    }
                });
            } catch (error) {
                log("ERROR", `No se pudo inicializar frida-il2cpp-bridge: ${error}`);
            }
        }
    } else if (lastModuleState !== "missing") {
        lastModuleState = "missing";
        log("WAIT", "libil2cpp.so todavía no está cargada; se volverá a comprobar durante 60 segundos.");
    }
}

try {
    if (Java.available) {
        Java.perform(() => {
            javaReported = true;
            log("OK", `Puente Java disponible. Android ${Java.androidVersion}`);
        });
    } else {
        log("INFO", "El proceso aún no expone una VM Java; el diagnóstico IL2CPP continuará igualmente.");
    }
} catch (error) {
    log("WARN", `No se pudo inicializar el puente Java: ${error}`);
}

checkIl2CppModule();
let checks = 0;
const poll = setInterval(() => {
    checks += 1;
    checkIl2CppModule();
    if (il2cppStarted || checks >= 60) {
        clearInterval(poll);
        if (!il2cppStarted) {
            log("ERROR", "No se detectó libil2cpp.so tras 60 segundos. Puede que el juego no use IL2CPP o que la biblioteca no se haya cargado.");
        }
        if (!javaReported) {
            log("INFO", "No se confirmó la disponibilidad de la VM Java en esta ejecución.");
        }
        log("DONE", "Finalizó la fase de detección inicial.");
    }
}, 1000);
