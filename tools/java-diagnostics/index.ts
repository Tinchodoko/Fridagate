import Java from "frida-java-bridge";

declare const console: {
    log(...data: unknown[]): void;
    warn(...data: unknown[]): void;
    error(...data: unknown[]): void;
};

const TAG = "[FG-JAVA-DIAGNOSTICS]";
const startedAt = Date.now();

function log(level: string, message: string): void {
    const elapsed = ((Date.now() - startedAt) / 1000).toFixed(3).padStart(8, " ");
    console.log(`${TAG} [${elapsed}] ${level} ${message}`);
}

console.log("[FRIDAGATE-SCRIPT-BOOT]");
log("BOOT", "Diagnóstico Java de FridaGate iniciado; frida-java-bridge está empaquetado en este bundle.");

try {
    log("INFO", `Frida runtime=${Script.runtime}; plataforma=${Process.platform}; arquitectura=${Process.arch}; PID=${Process.id}`);
} catch (error) {
    log("WARN", `No se pudo consultar toda la información del runtime: ${error}`);
}

try {
    log("INFO", `Java.available=${Java.available}`);
    if (Java.available) {
        Java.perform(() => {
            try {
                log("PASS", `Java.perform() ejecutado correctamente. Android=${Java.androidVersion}`);
            } catch (error) {
                log("ERROR", `Java.perform() comenzó, pero falló al consultar la versión Android: ${error}`);
            }

            try {
                const Version = Java.use("android.os.Build$VERSION");
                log("PASS", `android.os.Build.VERSION: release=${Version.RELEASE.value}; SDK_INT=${Version.SDK_INT.value}`);
            } catch (error) {
                log("ERROR", `Java bridge disponible, pero no se pudo leer android.os.Build.VERSION: ${error}`);
            }

            try {
                const ActivityThread = Java.use("android.app.ActivityThread");
                const app = ActivityThread.currentApplication();
                if (app !== null) {
                    const context = app.getApplicationContext();
                    log("PASS", `Contexto Android obtenido. Paquete=${context.getPackageName()}`);
                } else {
                    log("INFO", "ActivityThread.currentApplication() devolvió null; puede ser demasiado pronto en el ciclo de vida.");
                }
            } catch (error) {
                log("WARN", `No se pudo obtener el contexto mediante ActivityThread: ${error}`);
            }

            log("DONE", "Pruebas iniciales del puente Java finalizadas.");
        });
    } else {
        log("WARN", "Java.available=false. El bridge está incluido, pero esta sesión todavía no expone una VM Java.");
    }
} catch (error) {
    const detail = error instanceof Error ? (error.stack ?? error.message) : String(error);
    log("ERROR", `Fallo al inicializar frida-java-bridge: ${detail}`);
}
