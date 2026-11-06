package com.music.spotui.ui.repository

import com.music.spotui.di.Graph
import com.music.spotui.data.api.Api

class AppRepository constructor(private val api: Api = Graph.api) {

    suspend fun provideHomeFeed(facet: String = "") = api.getHomeFeed(facet)

    suspend fun searchEverything(query: String) = api.searchEverything(query)

    suspend fun provideArtistOverview(artistName: String, artistId: String = "") = api.getArtistOverview(artistName, artistId)

    /** Show (or book) page: header plus episodes / chapters in one flow. */
    suspend fun provideShowPage(showId: String) = api.getShowPage(showId)

    /** One episode by raw id, for the episode page. */
    suspend fun provideEpisode(episodeId: String) = api.getEpisode(episodeId)

    suspend fun provideLibrary() = api.getLibraryRoot()
    suspend fun provideLibraryFolder(uri: String) = api.getLibraryFolder(uri)

    suspend fun provideFollowedArtists() = api.getFollowedArtists()

    suspend fun provideCategoryPlaylists(genre: String) = api.getCategoryPlaylists(genre)

    suspend fun provideRecommendations(seedTrackIds: List<String>) = api.getRecommendations(seedTrackIds)

    suspend fun provideAccount() = api.getAccount()

    suspend fun provideCanvasUrl(trackId: String) = api.getCanvasUrl(trackId)
    fun knownCanvas(trackId: String) = api.knownCanvas(trackId)
}