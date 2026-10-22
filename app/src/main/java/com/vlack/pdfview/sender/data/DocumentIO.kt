package com.vlack.pdfview.sender.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.vlack.pdfview.sender.model.PickedDocument
import com.vlack.pdfview.sender.model.SendFormat
import java.io.File
import java.io.IOException

/**
 * Files handed to the Samsung Accessory SDK must exist on disk, so everything the user sends
 * from this app (composed text or a document picked through the system picker) is staged in the
 * cache directory first.
 */
object DocumentIO {
    private const val STAGING_DIR = "outbox"

    fun writeTextDocument(context: Context, rawName: String, format: SendFormat, text: String): File {
        val directory = stagingDirectory(context)
        val file = File(directory, buildDocumentFileName(rawName, format))
        file.writeText(text, Charsets.UTF_8)
        return file
    }

    fun importDocument(context: Context, uri: Uri): PickedDocument {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(context, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "document"
        val fileName = sanitizeImportedFileName(displayName)
        val target = File(stagingDirectory(context), fileName)

        resolver.openInputStream(uri).use { input ->
            if (input == null) throw IOException("Cannot open $uri")
            target.outputStream().use { output -> input.copyTo(output) }
        }

        return PickedDocument(
            name = fileName,
            sizeBytes = target.length(),
            path = target.absolutePath,
        )
    }

    fun discardStaged(context: Context, document: PickedDocument) {
        runCatching { File(document.path).delete() }
    }

    private fun stagingDirectory(context: Context): File =
        File(context.cacheDir, STAGING_DIR).apply { mkdirs() }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }
}
