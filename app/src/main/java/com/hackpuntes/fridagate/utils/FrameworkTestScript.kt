package com.hackpuntes.fridagate.utils

/**
 * Script de diagnóstico de primer toque para pruebas autorizadas.
 * Solo registra el primer ACTION_UP y la descripción de la vista Android;
 * no lee el texto introducido en campos de edición.
 */
object FrameworkTestScript {
    fun build(frameworkName: String, category: String): String {
        val label = frameworkName.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
        val type = category.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
        return """
            // Script Test By Tinchodoko "$label"
            (function () {
              var frameworkName = "$label";
              var category = "$type";
              var captured = false;
              var banner = null;
              function log(s) { console.log('[Script Test By Tinchodoko][' + frameworkName + '] ' + s); }
              if (!Java.available) { log('Java no disponible; test Android omitido.'); return; }
              Java.perform(function () {
                try {
                  var Activity = Java.use('android.app.Activity');
                  var dispatch = Activity.dispatchTouchEvent.overload('android.view.MotionEvent');
                  Activity.onResume.implementation = function () {
                    var r = this.onResume();
                    try {
                      var content = this.findViewById(0x01020002);
                      if (content && !banner) {
                        var TextView = Java.use('android.widget.TextView');
                        var FrameParams = Java.use('android.widget.FrameLayout$LayoutParams');
                        var Gravity = Java.use('android.view.Gravity');
                        banner = TextView.$new(this);
                        banner.setText('Hola Mundo — Script Test By Tinchodoko: ' + frameworkName);
                        banner.setTextColor(-1);
                        banner.setTextSize(12);
                        banner.setPadding(16, 10, 16, 10);
                        banner.setBackgroundColor(0xDD202124);
                        banner.setClickable(false);
                        banner.setFocusable(false);
                        banner.setContentDescription('fridagate_test_banner');
                        var p = FrameParams.$new(-2, -2, Gravity.TOP.value | Gravity.LEFT.value);
                        p.setMargins(12, 36, 0, 0);
                        content.addView(banner, p);
                        log('Hola Mundo: banner de prueba visible. Categoría: ' + category);
                      }
                    } catch (e) { log('Banner no disponible: ' + e); }
                    return r;
                  };
                  dispatch.implementation = function (event) {
                    var result = dispatch.call(this, event);
                    if (!captured && event && event.getActionMasked() === 1) {
                      captured = true;
                      var x = event.getRawX(), y = event.getRawY();
                      var root = this.findViewById(0x01020002);
                      var target = null;
                      try {
                        var loc = Java.array('int', [0, 0]);
                        root.getLocationOnScreen(loc);
                        if (x >= loc[0] && x <= loc[0] + root.getWidth() && y >= loc[1] && y <= loc[1] + root.getHeight()) target = root;
                      } catch (_) {}
                      var details = 'vista Android raíz';
                      if (target) {
                        try { details = 'clase=' + target.getClass().getName(); } catch (_) {}
                        try {
                          var id = target.getId();
                          if (id !== -1) details += ', id=' + target.getResources().getResourceEntryName(id);
                        } catch (_) {}
                        try {
                          var description = target.getContentDescription();
                          if (description) details += ', descripción=' + description;
                        } catch (_) {}
                      }
                      log('PRIMER TOQUE: ACTION_UP, x=' + x + ', y=' + y + '; elemento=' + details);
                      log('Nota: en motores que dibujan toda la interfaz (p. ej. Unity/Unreal/Godot), Android puede exponer solo la superficie y las coordenadas.');
                    }
                    return result;
                  };
                  log('Test instalado. Esperando el primer toque; no lee contraseñas ni texto escrito.');
                } catch (e) { log('No se pudo instalar la prueba: ' + e); }
              });
            })();
        """.trimIndent()
    }
}
