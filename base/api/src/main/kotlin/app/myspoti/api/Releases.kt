package app.myspoti.api

/**
 * An artist's releases, newest first, from the overview's popular releases and the discography.
 * The discography comes from Spotify already in date order (DATE_DESC) with full dates; a popular
 * release may carry its year alone. Sorted by date as strings, a year-only "2024" fell below every
 * dated release of 2024, so singles and EPs of one year came mixed and the "latest release" was
 * wrong (reported). Here a release keeps its most precise date, the years go newest first, and
 * within a year the discography's own order leads.
 */
object Releases {
    fun newestFirst(popular: List<Album>, discography: List<Album>): List<Album> {
        val best = LinkedHashMap<Uri, Album>()
        for (a in discography + popular) {
            val known = best[a.uri]
            if (known == null || a.releaseDate.orEmpty().length > known.releaseDate.orEmpty().length) best[a.uri] = a
        }
        val rank = HashMap<Uri, Int>()
        discography.forEachIndexed { i, a -> rank.putIfAbsent(a.uri, i) }
        return best.values.sortedWith(
            compareByDescending<Album> { year(it) }
                .thenBy { rank[it.uri] ?: Int.MAX_VALUE }
                .thenByDescending { it.releaseDate.orEmpty() },
        )
    }

    private fun year(a: Album): Int = a.releaseDate.orEmpty().take(4).toIntOrNull() ?: 0
}
