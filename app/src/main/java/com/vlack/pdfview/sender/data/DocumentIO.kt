package com.vlack.pdfview.sender.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.vlack.pdfview.sender.model.PickedDocument
import com.vlack.pdfview.sender.model.SendFormat
import java.io.File
import androidx.core.content.ContextCompat
import java.io.IOException

/**
 * Files handed to the Samsung Accessory SDK must exist on disk, and the transfer itself is done by
 * the separate Samsung Accessory Service app, so the file has to be readable by another app.
 * That rules out the private cache directory: everything the user sends from this app (composed
 * text or a document picked through the system picker) is staged in shared storage instead,
 * in Documents/PDFview.
 */
object DocumentIO {
    private const val STAGING_DIR = "PDFview"

    /** True when files can be written to (and read by other apps from) shared storage. */
    fun hasSharedStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }

    fun writeTextDocument(context: Context, rawName: String, format: SendFormat, text: String): File {
        val directory = stagingDirectory(context)
        val file = uniqueFile(directory, buildDocumentFileName(rawName, format))
        file.writeText(text, Charsets.UTF_8)
        return file
    }

    fun importDocument(context: Context, uri: Uri): PickedDocument {
        // Like the original app, send the file straight from where it lives whenever the picked
        // document maps to a real path; copying is only a fallback.
        resolveRealFile(context, uri)?.let { file ->
            return PickedDocument(
                name = file.name,
                sizeBytes = file.length(),
                path = file.absolutePath,
                staged = false,
            )
        }

        val resolver = context.contentResolver
        val displayName = queryDisplayName(context, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "document"
        val fileName = sanitizeImportedFileName(displayName)
        val target = uniqueFile(stagingDirectory(context), fileName)

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
        if (document.staged) runCatching { File(document.path).delete() }
    }

    /** Best-effort conversion of a picked document URI into a real, readable file path. */
    private fun resolveRealFile(context: Context, uri: Uri): File? {
        if (!hasSharedStorageAccess(context)) return null

        fun File.usable(): File? = takeIf { it.isFile && it.canRead() }

        if (uri.scheme == "file") return uri.path?.let { File(it).usable() }

        if (DocumentsContract.isDocumentUri(context, uri)) {
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            if (docId != null) {
                when (uri.authority) {
                    "com.android.externalstorage.documents" -> {
                        val volume = docId.substringBefore(':')
                        val relative = docId.substringAfter(':', "")
                        val base = if (volume.equals("primary", ignoreCase = true)) {
                            Environment.getExternalStorageDirectory()
                        } else {
                            File("/storage/$volume")
                        }
                        File(base, relative).usable()?.let { return it }
                    }
                    "com.android.providers.downloads.documents" -> {
                        if (docId.startsWith("raw:")) {
                            File(docId.removePrefix("raw:")).usable()?.let { return it }
                        }
                    }
                }
            }
        }

        // Many providers (downloads, media) still expose the path in the legacy _data column.
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.let { File(it).usable() }
    }

    /**
     * The watch app refuses to overwrite an existing file ("The file write request failed"), so every
     * send gets a name that has not been staged before: "notes.txt", "notes (2).txt", ...
     */
    private fun uniqueFile(directory: File, fileName: String): File {
        var candidate = File(directory, fileName)
        if (!candidate.exists()) return candidate
        val base = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var index = 2
        while (candidate.exists()) {
            candidate = File(directory, "$base ($index)$extension")
            index++
        }
        return candidate
    }

    private fun stagingDirectory(context: Context): File {
        val shared = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            STAGING_DIR,
        )
        if (hasSharedStorageAccess(context) && (shared.isDirectory || shared.mkdirs())) {
            return shared
        }
        throw IOException("Shared storage is not available: $shared")
    }

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
