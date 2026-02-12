package com.xreal.nativear.utils

import android.content.Context
import android.util.Log
import com.xreal.nativear.utils.LogRotation
import java.io.File
import java.io.FileOutputStream

/**
 * LogRotation: Utility for managing log file size and rotation.
 */
object LogRotation {
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024 // 5MB
    private const val MAX_LOG_FILES = 3
    private const val TAG = "LogRotation"

    fun rotateIfNeeded(logFile: File) {
        if (!logFile.exists() || logFile.length() < MAX_LOG_SIZE) return

        Log.i(TAG, "Rotating log file: ${logFile.name} (Size: ${logFile.length()})")
        val parent = logFile.parentFile ?: return
        val baseName = logFile.nameWithoutExtension
        val extension = logFile.extension

        // 1. Delete the oldest log file (log.3.txt)
        val oldest = File(parent, "$baseName.$MAX_LOG_FILES.$extension")
        if (oldest.exists()) oldest.delete()

        // 2. Shift existing logs: log.2.txt -> log.3.txt, log.1.txt -> log.2.txt
        for (i in (MAX_LOG_FILES - 1) downTo 1) {
            val current = File(parent, "$baseName.$i.$extension")
            if (current.exists()) {
                val next = File(parent, "$baseName.${i + 1}.$extension")
                current.renameTo(next)
            }
        }

        // 3. Rename current log.txt -> log.1.txt
        val firstBackup = File(parent, "$baseName.1.$extension")
        logFile.renameTo(firstBackup)

        // 4. Create fresh log.txt
        logFile.createNewFile()
    }
}
