package eu.draconest.hermesbots.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Przechwytuje nieobsłużone wyjątki i zapisuje stack trace do pliku,
 * żeby następne uruchomienie apki mogło pokazać co się stało
 * (bez adb / logcata).
 */
object CrashGuard {

    private lateinit var file: File

    /** Instaluje handler; zwraca treść poprzedniego crasha (jeśli był). */
    fun install(context: Context): String? {
        file = File(context.filesDir, "last_crash.txt")
        val previous = if (file.exists()) {
            file.readText().trim().takeIf { it.isNotEmpty() }
        } else null
        file.delete()

        val platformDefault = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                file.writeText(
                    "thread=${thread.name}\n" +
                        Log.getStackTraceString(throwable)
                )
            } catch (_: Exception) {
                // ostatnia deska ratunku — nic więcej nie możemy zrobić
            }
            platformDefault?.uncaughtException(thread, throwable)
        }
        return previous
    }
}
