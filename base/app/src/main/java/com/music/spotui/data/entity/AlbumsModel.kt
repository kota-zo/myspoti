package com.music.spotui.data.entity

@kotlinx.serialization.Serializable
data class AlbumsModel(
    val id : Int,
    val artists : String,
    val coverUri : String,
    val name : String,
    // Release date as Spotify gives it: "2024-05-17", or just "2024" when only the year is known.
    // Sorts as text, newest last; [year] is what the screens show.
    val time : String,
    // Release kind from Spotify: "album", "single", "compilation"... Empty when unknown.
    val type : String = "",
    // Real Spotify album id (empty when unknown) — the page is loaded by id.
    val spotifyId : String = "",
){
    constructor() : this( -1,"" ,"" ,"", "")

    /** The release year ("2024"), or empty when the date is unknown. */
    val year : String get() = time.take(4)
}
