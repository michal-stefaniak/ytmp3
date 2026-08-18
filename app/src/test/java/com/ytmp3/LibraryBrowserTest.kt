package com.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryBrowserTest {
    @Test
    fun `projects without exported samples remain visible`() {
        val project = SampleProject(id = "project", sourceUri = "content://source", title = "Imported loop")

        assertEquals(
            listOf(LibraryRow.Project(project)),
            LibraryBrowser.rows(listOf(project), emptyList(), query = "", tag = "", favouritesOnly = false)
        )
    }

    @Test
    fun `project search shows matching project and its samples`() {
        val project = SampleProject(id = "project", sourceUri = "content://source", title = "Drum loop")
        val sample = SampleRecord("sample", "project", 0, 100, "content://output", 100, "WAV", label = "Kick")

        assertEquals(
            listOf(LibraryRow.Project(project), LibraryRow.Sample(sample, project)),
            LibraryBrowser.rows(listOf(project), listOf(sample), query = "drum", tag = "", favouritesOnly = false)
        )
    }
}
