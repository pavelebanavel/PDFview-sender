package com.vlack.pdfview.sender.data

import com.vlack.pdfview.sender.model.SendFormat

private val INVALID_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
private val WHITESPACE = Regex("\\s+")
private val DOCUMENT_EXTENSION = Regex("\\.(txt|md|markdown|pdf)$", RegexOption.IGNORE_CASE)
private const val MAX_BASE_NAME_LENGTH = 64

/**
 * Turns whatever the user typed into a safe document base name without an extension.
 * "  My / notes.md  " -> "My notes"
 */
fun sanitizeBaseName(raw: String): String {
    val cleaned = raw
        .trim()
        .replace(INVALID_FILE_NAME_CHARS, " ")
        .replace(WHITESPACE, " ")
        .trim(' ', '.')
        .replace(DOCUMENT_EXTENSION, "")
        .trim(' ', '.')
        .take(MAX_BASE_NAME_LENGTH)
        .trim(' ', '.')

    return cleaned.ifBlank { "document" }
}

/** Complete file name for a composed document, e.g. "notes.md". */
fun buildDocumentFileName(raw: String, format: SendFormat): String =
    "${sanitizeBaseName(raw)}.${format.extension}"

/** Keeps the original name (and extension) of an imported document, only cleaning unsafe characters. */
fun sanitizeImportedFileName(raw: String): String {
    val name = raw
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .trim()
        .replace(INVALID_FILE_NAME_CHARS, " ")
        .replace(WHITESPACE, " ")
        .trim(' ', '.')

    return name.ifBlank { "document" }.take(96)
}
