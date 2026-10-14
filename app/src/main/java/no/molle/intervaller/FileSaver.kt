package no.molle.intervaller

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Lagrer .tcx-filen i Nedlastinger, der Strava- og Garmin-opplasting finner den. */
object FileSaver {
    fun save(c: Context, name: String, content: String): String {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "molle.tcx" }
        val bytes = content.toByteArray(Charsets.UTF_8)
        // Prøv vanlig navn først, så med tillegg (-2, -3 …) hvis navnet er opptatt i Nedlastinger
        var first = ""
        val base = safe.substringBeforeLast('.', safe)
        val ext = if (safe.contains('.')) "." + safe.substringAfterLast('.') else ""
        for (i in 1..6) {
            val tryName = if (i == 1) safe else "$base-$i$ext"
            try {
                saveToDownloads(c, tryName, bytes)
                return "ok"
            } catch (e: Exception) {
                if (first.isEmpty()) first = e.message ?: e.javaClass.simpleName
            }
        }
        // Reserve: appens egen mappe, som alltid er skrivbar
        return try {
            val dir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: c.filesDir
            dir.mkdirs()
            File(dir, safe).writeBytes(bytes)
            "Nedlastinger var ikke tilgjengelig. Filen ligger i Android/data/no.molle.intervaller/files/Download."
        } catch (e: Exception) {
            "$first / ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun saveToDownloads(c: Context, name: String, bytes: ByteArray) {
        val resolver = c.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("kunne ikke opprette filen")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("kunne ikke skrive til filen")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
    }
}
