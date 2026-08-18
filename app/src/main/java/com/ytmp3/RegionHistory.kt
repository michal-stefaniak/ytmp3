package com.ytmp3

/** In-memory edit history for a project's editable region list. */
class RegionHistory(initial: List<SampleRegion>) {
    private val undo = ArrayDeque<List<SampleRegion>>()
    private val redo = ArrayDeque<List<SampleRegion>>()
    private var current = initial

    fun push(next: List<SampleRegion>) {
        if (next == current) return
        undo.addLast(current)
        current = next
        redo.clear()
    }

    fun undo(): List<SampleRegion> = undo.removeLastOrNull()?.also {
        redo.addLast(current)
        current = it
    } ?: current

    fun redo(): List<SampleRegion> = redo.removeLastOrNull()?.also {
        undo.addLast(current)
        current = it
    } ?: current

    fun current(): List<SampleRegion> = current

    fun canUndo(): Boolean = undo.isNotEmpty()

    fun canRedo(): Boolean = redo.isNotEmpty()
}
