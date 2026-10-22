package com.vlack.pdfview.sender.data

import com.vlack.pdfview.sender.model.SendFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DocumentNamesTest {

    @Test
    fun `strips extension and unsafe characters from the base name`() {
        assertEquals("My notes", sanitizeBaseName("  My / notes.md  "))
    }

    @Test
    fun `keeps a blank name usable`() {
        assertEquals("document", sanitizeBaseName("   ...   "))
    }

    @Test
    fun `switches extension to the selected format`() {
        assertEquals("report.md", buildDocumentFileName("report.txt", SendFormat.Md))
        assertEquals("report.txt", buildDocumentFileName("report.md", SendFormat.Txt))
    }

    @Test
    fun `appends extension when the user did not type one`() {
        assertEquals("lecture.txt", buildDocumentFileName("lecture", SendFormat.Txt))
    }

    @Test
    fun `imported document keeps its name but loses its path`() {
        assertEquals("coursework.pdf", sanitizeImportedFileName("/Download/coursework.pdf"))
        assertEquals("notes.md", sanitizeImportedFileName("C:\\docs\\notes.md"))
    }

    @Test
    fun `imported document name cannot escape the staging directory`() {
        val name = sanitizeImportedFileName("../../etc/passwd")
        assertFalse(name.contains('/'))
        assertFalse(name.startsWith(".."))
    }
}
