package no.molle.intervaller

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore

/** Lagrer .tcx-filen i Nedlastinger, der Strava- og Garmin-opplasting finner den. */
object FileSaver {
    fun save(c: Context, name: String, content: String): String {
        return try {
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "molle.tcx" }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "Fikk ikke lagret filen."
            c.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                ?: return "Fikk ikke lagret filen."
            "ok"
        } catch (e: Exception) {
            "Fikk ikke lagret filen: ${e.message ?: "ukjent feil"}"
        }
    }
}
