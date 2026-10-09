package com.hackpuntes.fridagate.utils

/**
 * Script de diagnóstico de primer toque para pruebas autorizadas.
 * Registra el primer toque y la descripción de la vista Android, sin leer campos de edición.
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

              function log(s) {
                console.log('[Script Test By Tinchodoko][' + frameworkName + '] ' + s);
              }

              function findViewAt(view, x, y) {
                try {
                  if (!view || !view.isShown()) return null;
                  try {
                    if (String(view.getContentDescription()) === 'fridagate_test_banner') return null;
                  } catch (_) {}
                  var location = Java.array('int', [0, 0]);
                  view.getLocationOnScreen(location);
                  var left = location[0], top = location[1];
                  if (x < left || x > left + view.getWidth() || y < top || y > top + view.getHeight()) return null;
                  try {
                    var group = Java.cast(view, Java.use('android.view.ViewGroup'));
                    for (var i = group.getChildCount() - 1; i >= 0; i--) {
                      var child = findViewAt(group.getChildAt(i), x, y);
                      if (child) return child;
                    }
                  } catch (_) {}
                  return view;
                } catch (_) { return null; }
              }

              function describeView(view) {
                if (!view) return 'no se pudo identificar una vista Android';
                var parts = [];
                try { parts.push('clase=' + view.getClass().getName()); } catch (_) {}
                try {
                  var id = view.getId();
                  if (id !== -1) parts.push('id=' + view.getResources().getResourceEntryName(id));
                } catch (_) {}
                try {
                  var description = view.getContentDescription();
                  if (description) parts.push('contentDescription="' + String(description) + '"');
                } catch (_) {}
                try {
                  var className = String(view.getClass().getName());
                  if (className.indexOf('EditText') === -1) {
                    var TextView = Java.use('android.widget.TextView');
                    if (TextView.class.isInstance(view)) {
                      var text = Java.cast(view, TextView).getText();
                      if (text) parts.push('texto="' + String(text).replace(/\s+/g, ' ').slice(0, 100) + '"');
                    }
                  }
                } catch (_) {}
                try { if (view.isClickable()) parts.push('clickable=true'); } catch (_) {}
                return parts.join(', ');
              }

              if (!Java.available) {
                log('Java no disponible; test Android omitido.');
                return;
              }

              Java.perform(function () {
                try {
                  var Activity = Java.use('android.app.Activity');
                  var dispatch = Activity.dispatchTouchEvent.overload('android.view.MotionEvent');
                  var onResume = Activity.onResume.overload();

                  onResume.implementation = function () {
                    var result = onResume.call(this);
                    try {
                      var content = this.findViewById(0x01020002);
                      if (content && !banner) {
                        var TextView = Java.use('android.widget.TextView');
                        var FrameParams = Java.use('android.widget.FrameLayout${'$'}LayoutParams');
                        var Gravity = Java.use('android.view.Gravity');
                        banner = TextView.${'$'}new(this);
                        banner.setText('Hola Mundo — Script Test By Tinchodoko: ' + frameworkName);
                        banner.setTextColor(-1);
                        banner.setTextSize(12);
                        banner.setPadding(16, 10, 16, 10);
                        banner.setBackgroundColor(0xDD202124);
                        banner.setClickable(false);
                        banner.setFocusable(false);
                        banner.setContentDescription('fridagate_test_banner');
                        var params = FrameParams.${'$'}new(-2, -2, Gravity.TOP.value | Gravity.LEFT.value);
                        params.setMargins(12, 36, 0, 0);
                        content.addView(banner, params);
                        log('Hola Mundo: banner de prueba visible. Categoría: ' + category);
                      }
                    } catch (e) {
                      log('Banner no disponible: ' + e);
                    }
                    return result;
                  };

                  dispatch.implementation = function (event) {
                    var result = dispatch.call(this, event);
                    if (!captured && event && event.getActionMasked() === 1) {
                      captured = true;
                      var x = event.getRawX(), y = event.getRawY();
                      var root = this.findViewById(0x01020002);
                      var target = findViewAt(root, x, y);
                      var details = describeView(target);
                      log('PRIMER TOQUE: acción=ACTION_UP, x=' + x + ', y=' + y + '; elemento=' + details);
                      if (banner) {
                        try {
                          banner.setText('Hola Mundo — Script Test By Tinchodoko: ' + frameworkName + '\nPrimer toque: ' + details.slice(0, 110));
                        } catch (_) {}
                      }
                      log('Nota: motores como Unity/Unreal/Godot pueden exponer solo la superficie y las coordenadas.');
                    }
                    return result;
                  };

                  log('Test instalado. Esperando el primer toque; no se lee texto escrito en campos.');
                } catch (e) {
                  log('No se pudo instalar la prueba: ' + e);
                }
              });
            })();
        """.trimIndent()
    }
}
