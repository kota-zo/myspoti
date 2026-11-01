package com.music.spotui.di

/**
 * What [LocalPlayer] does with the songs of its queue whose downloaded file was deleted while it
 * played (OfflineStore.remove, removeAll), kept pure so it is tested off-device. Such a song no
 * longer plays from the phone: it plays from its source as a song of the list that was never
 * downloaded does (the extensions, else Spotify: see LocalPlayer.onPlayerError), or, with no
 * connection (or the data saver on), it is left out of the queue. The song playing is kept: its file
 * is still open and it ends as usual.
 */
object DeletedDownloads {
    enum class Fix { KEEP, STREAM, DROP }

    /**
     * One [Fix] per queue item: [gone] holds, for each, the Spotify id of its deleted download (""
     * when it has none, an episode), or null when its download was not deleted; [current] is the
     * song playing, [online] whether a source can be reached.
     */
    fun plan(gone: List<String?>, current: Int, online: Boolean): List<Fix> = gone.mapIndexed { i, id ->
        when {
            id == null || i == current -> Fix.KEEP
            id.isBlank() || !online -> Fix.DROP
            else -> Fix.STREAM
        }
    }

    /** True when a playback error says the item's file is not there ([code]: PlaybackException's, [cause]: its cause). */
    fun isMissingFile(code: Int, cause: Throwable?): Boolean =
        code == androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
            generateSequence(cause) { it.cause }.take(8).any { it is java.io.FileNotFoundException }
}
