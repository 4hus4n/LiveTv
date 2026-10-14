package com.ahusan.livetv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves the last crash to a file and shows it on the next launch. */
object CrashLog {
    private var installed = false

    private fun file(context: Context) = File(context.applicationContext.filesDir, "last_crash.txt")

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                file(app).writeText(
                    "Live TV ${versionName(app)} · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}\n$time\n\n$sw"
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun showIfAny(activity: Activity) {
        val f = file(activity)
        if (!f.exists()) return
        val text = f.readText().take(3000)
        f.delete()
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Live TV closed unexpectedly last time")
            .setMessage("Take a photo of this and send it to fix the problem.\n\n$text")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }
}
