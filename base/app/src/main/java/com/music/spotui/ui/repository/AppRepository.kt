package com.music.spotui.ui.repository

import com.music.spotui.di.Graph
import com.music.spotui.data.api.Api

class AppRepository constructor(private val api: Api = Graph.api) {

    suspend fun provideAlbums() = api.getAlbums()

    suspend fun provideHomeFeed(facet: String = "") = api.getHomeFeed(facet)

    suspend fun provideArtists() = api.getArtists()

    suspend fun provideSongs() = api.getSongs()

    suspend fun searchSongs(query: String) = api.searchTracks(query)

    suspend fun searchEverything(query: String) = api.searchEverything(query)

    suspend fun provideAlbumSongs(albumName: String, artist: String = "") = api.getAlbumSongs(albumName, artist)

    suspend fun provideArtistSongs(artistName: String) = api.getArtistSongs(artistName)

    suspend fun provideArtistOverview(artistName: String, artistId: String = "") = api.getArtistOverview(artistName, artistId)

    suspend fun providePlaylistSongs(playlistId: String) = api.getPlaylistSongs(playlistId)

    suspend fun providePlaylist(playlistId: String) = api.getPlaylist(playlistId)

    suspend fun provideShowEpisodes(showId: String) = api.getShowEpisodes(showId)

    suspend fun provideShow(showId: String) = api.getShow(showId)

    /** Show (or book) page: header plus episodes / chapters in one flow. */
    suspend fun provideShowPage(showId: String) = api.getShowPage(showId)

    /** One episode by raw id, for the episode page. */
    suspend fun provideEpisode(episodeId: String) = api.getEpisode(episodeId)

    suspend fun provideLibrary() = api.getLibraryRoot()
    suspend fun provideLibraryFolder(uri: String) = api.getLibraryFolder(uri)

    suspend fun provideFollowedArtists() = api.getFollowedArtists()
    suspend fun provideFollowedShows() = api.getFollowedShows()

    suspend fun provideCategoryPlaylists(genre: String) = api.getCategoryPlaylists(genre)

    suspend fun provideRecommendations(seedTrackIds: List<String>) = api.getRecommendations(seedTrackIds)

    suspend fun provideLikedSongs() = api.getLikedSongs()

    suspend fun provideAccount() = api.getAccount()

    suspend fun provideCanvasUrl(trackId: String) = api.getCanvasUrl(trackId)
    fun knownCanvas(trackId: String) = api.knownCanvas(trackId)
}