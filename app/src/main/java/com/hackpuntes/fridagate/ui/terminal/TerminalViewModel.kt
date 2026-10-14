package com.hackpuntes.fridagate.ui.terminal

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

/**
 * Keeps terminal session state alive when the user switches bottom-navigation tabs.
 * The terminal output/history, current directory and root preference survive while
 * the activity's ViewModelStore remains alive.
 */
class TerminalViewModel : ViewModel() {
    val lines = mutableStateListOf(
        "~ FridaGate 2.0 — Terminal integrada",
        "~ Comandos ejecutados con el usuario de la app por defecto.",
        "~ Usa el menú ⋮ para activar ejecución root cuando la necesites.",
        "~ "
    )
    val history = mutableStateListOf<String>()
    val command = mutableStateOf("")
    val historyIndex = mutableIntStateOf(-1)
    val rootEnabled = mutableStateOf(false)
    val busy = mutableStateOf(false)
    val currentDirectory = mutableStateOf("/sdcard")
}
