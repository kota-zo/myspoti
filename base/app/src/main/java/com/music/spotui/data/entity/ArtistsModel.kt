package com.music.spotui.data.entity

@kotlinx.serialization.Serializable
data class ArtistsModel(
    val name : String,
    val coverUri : String,
    // Real Spotify artist id — open the EXACT artist (so "RAM" doesn't resolve to
    // "Rammstein" via a fuzzy name search). Empty when unknown.
    val id : String = "",
    /** When the user followed the artist (ISO 8601), from the library; empty when unknown. */
    val addedAt : String = "",
){
    constructor() : this("", "", "")
}
