(function () {
    "use strict";

    var PREFIX = "[FRIDAGATE-TEST]";

    function log(message) {
        console.log(PREFIX + " " + message);
    }

    function error(section, err) {
        log("ERROR en " + section + ": " +
            (err && err.message ? err.message : String(err)));
    }

    log("========== INICIO DEL DIAGNÓSTICO ==========");

    // TEST 1: ejecución real del JavaScript
    try {
        log("TEST 1 PASS: JavaScript ejecutándose dentro del proceso.");
    } catch (e) {
        error("JavaScript", e);
    }

    // TEST 2: versión del runtime Frida
    try {
        var fridaVersion =
            typeof Frida !== "undefined" && Frida.version
                ? Frida.version
                : "no expuesta por este runtime";

        log("TEST 2: Runtime Frida = " + fridaVersion);
    } catch (e) {
        error("Runtime Frida", e);
    }

    // TEST 3: proceso, plataforma y arquitectura
    try {
        log("TEST 3 PASS: PID = " + Process.id);
        log("Plataforma = " + Process.platform);
        log("Arquitectura = " + Process.arch);
        log("Tamaño de puntero = " + Process.pointerSize + " bytes");
    } catch (e) {
        error("Información del proceso", e);
    }

    // TEST 4: enumeración de módulos nativos
    try {
        var modules = Process.enumerateModules();

        log("TEST 4 PASS: módulos enumerados = " + modules.length);

        var important = [
            "libc.so",
            "libart.so",
            "libandroid.so",
            "libil2cpp.so",
            "libunity.so",
            "libflutter.so"
        ];

        important.forEach(function (name) {
            var found = modules.some(function (module) {
                return module.name === name;
            });

            log("Módulo " + name + ": " +
                (found ? "PRESENTE" : "NO DETECTADO"));
        });

        log("Primeros módulos cargados:");

        modules.slice(0, 12).forEach(function (module) {
            log("  " + module.name +
                " | base=" + module.base +
                " | tamaño=" + module.size);
        });
    } catch (e) {
        error("Enumeración de módulos", e);
    }

    // TEST 5: enumeración de hilos
    try {
        var threads = Process.enumerateThreads();

        log("TEST 5 PASS: hilos enumerados = " + threads.length);

        threads.slice(0, 10).forEach(function (thread) {
            log("  Hilo ID=" + thread.id +
                " nombre=" + (thread.name || "(sin nombre)"));
        });
    } catch (e) {
        error("Enumeración de hilos", e);
    }

    // TEST 6: disponibilidad del puente Java
    // Nota: frida-inject no garantiza por sí solo que Java esté definido.
    // La ausencia de Java no demuestra automáticamente que el modo de inyección sea incorrecto.
    try {
        if (typeof Java === "undefined") {
            log("TEST 6 FALLO: Java no está expuesto en este runtime.");
            log("  Causa posible: el agente no incluye/carga frida-java-bridge.");
            log("  Verifica que el diagnóstico se ejecute desde el bundle con frida-java-bridge integrado.");
            log("  Verifica el servidor con frida-ps -U desde un entorno que tenga frida-tools.");
            log("  La inspección nativa puede funcionar aunque Java.use()/Java.perform() no estén disponibles.");
        } else if (!Java.available) {
            log("TEST 6 PARCIAL: Java existe, pero la VM no está disponible en este momento.");
            log("  Puede ser una cuestión de tiempo de inicialización; vuelve a probar con la aplicación ya iniciada.");
        } else {
            log("TEST 6 INFO: Java bridge detectado; comprobando la VM de Android.");
            Java.perform(function () {
                try {
                    log("TEST 6 PASS: Java.perform() se ejecutó correctamente.");
                    log("  Android version = " + Java.androidVersion);
                    try {
                        var Activity = Java.use("android.app.Activity");
                        log("  android.app.Activity: ACCESIBLE");
                        if (Activity && Activity.$dispose) {
                            Activity.$dispose();
                        }
                    } catch (e) {
                        log("  android.app.Activity: ERROR - " +
                            (e && e.message ? e.message : String(e)));
                    }
                } catch (e) {
                    error("Comprobación dentro de Java.perform()", e);
                }
            });
        }
    } catch (e) {
        error("Puente Java", e);
    }

    // TEST 7: detección de módulos relevantes para el proceso
    try {
        var names = Process.enumerateModules().map(function (module) {
            return module.name.toLowerCase();
        });

        log("TEST 7: entorno nativo inspeccionado.");
        log("Unity detectado = " +
            names.some(function (name) {
                return name.indexOf("unity") !== -1 ||
                       name === "libil2cpp.so";
            }));

        log("Flutter detectado = " +
            names.some(function (name) {
                return name.indexOf("flutter") !== -1;
            }));
    } catch (e) {
        error("Detección de frameworks", e);
    }

    log("========== DIAGNÓSTICO FINALIZADO ==========");
})();
