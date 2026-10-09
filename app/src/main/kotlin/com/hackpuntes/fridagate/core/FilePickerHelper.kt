package com.hackpuntes.fridagate.core

import android.content.Context
import android.os.Environment
import java.io.File

object FilePickerHelper {
    
    fun getScriptsDirectory(context: Context): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "Fridagate2.0/Scripts"
        )
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
    
    fun getJsFiles(context: Context): List<File> {
        return try {
            getScriptsDirectory(context)
                .listFiles { file -> file.extension == "js" }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    fun readJsFile(file: File): String? {
        return try {
            file.readText()
        } catch (e: Exception) {
            null
        }
    }
    
    fun getScriptPath(context: Context, fileName: String): File {
        return File(getScriptsDirectory(context), fileName)
    }
}
