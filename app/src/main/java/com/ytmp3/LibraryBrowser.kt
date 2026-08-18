package com.ytmp3

sealed interface LibraryRow {
    data class Project(val project: SampleProject) : LibraryRow
    data class Sample(val sample: SampleRecord, val project: SampleProject) : LibraryRow
}

/** Pure filtering so the library keeps imported projects visible before any export exists. */
object LibraryBrowser {
    fun rows(
        projects: List<SampleProject>,
        samples: List<SampleRecord>,
        query: String,
        tag: String,
        favouritesOnly: Boolean
    ): List<LibraryRow> {
        val normalizedQuery = query.trim().lowercase()
        val normalizedTag = tag.trim().lowercase()
        val samplesByProject = samples.groupBy { it.projectId }
        return projects.flatMap { project ->
            val projectMatchesQuery = normalizedQuery.isEmpty() || project.title.lowercase().contains(normalizedQuery)
            val matchingSamples = samplesByProject[project.id].orEmpty().filter { sample ->
                val sampleMatchesQuery = normalizedQuery.isEmpty() || projectMatchesQuery ||
                    sample.label.lowercase().contains(normalizedQuery) || sample.tags.any { it.lowercase().contains(normalizedQuery) }
                sampleMatchesQuery &&
                    (normalizedTag.isEmpty() || sample.tags.any { it.equals(normalizedTag, ignoreCase = true) }) &&
                    (!favouritesOnly || sample.favourite)
            }
            val showProject = projectMatchesQuery && normalizedTag.isEmpty() && !favouritesOnly || matchingSamples.isNotEmpty()
            buildList {
                if (showProject) add(LibraryRow.Project(project))
                if (showProject) matchingSamples.forEach { add(LibraryRow.Sample(it, project)) }
            }
        }
    }
}
