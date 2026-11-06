package com.music.spotui.data.entity

/**
 * A podcast show (search result / show header). [id] is the RAW Spotify show id
 * so the detail screen can fetch its episodes. Episodes themselves are modelled
 * as [SongsModel] with url = "episode:<rawId>" so they flow through the existing
 * queue/player (SongPlayer routes that prefix to the web player's episode page).
 */
data class PodcastModel(
    val id: String,
    val name: String,
    val publisher: String,
    val coverUri: String,
    /** Show description ("About" tab, episode page header). */
    val description: String = "",
    /** Average rating (0 = none shown). */
    val rating: Double = 0.0,
    /** Spotify's own follow state for this show. */
    val saved: Boolean = false,
    /** Audiobooks: authors and narrators, empty for a podcast. */
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    /** The page is a book: its items are chapters, not episodes. */
    val audiobook: Boolean = false,
) {
    constructor() : this("", "", "", "")

    /** "de <author> • lu par <narrator>" for a book, the publisher for a podcast. */
    val byline: String get() = when {
        authors.isNotEmpty() && narrators.isNotEmpty() -> authors.joinToString(", ") + " • " + narrators.joinToString(", ")
        authors.isNotEmpty() -> authors.joinToString(", ")
        else -> publisher
    }
}
