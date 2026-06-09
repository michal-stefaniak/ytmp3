package com.ytmp3

data class PlaylistEntry(
    val id: String,
    val title: String,
    val url: String,
    var selected: Boolean = true
)
