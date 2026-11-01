package com.music.spotui.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Replaces [file] with [text] whole or not at all: written to a temporary file next to it, flushed
 * to the disk, then moved over it. A move that fails (the target in the way on some file systems)
 * is tried once more with the old file moved aside first, and put back if that fails too: the old
 * file is never lost, and an [IOException] says the new text was not saved.
 */
fun writeTextSafely(file: File, text: String, sync: Boolean = true) = writeBytesSafely(file, text.toByteArray(), sync)

/**
 * [writeTextSafely] for [bytes]: a [NotReplacedException] when only the move failed (the old file kept).
 * [sync] false skips the flush to the disk, for frequent writes of what can be lost in a power cut.
 */
fun writeBytesSafely(file: File, bytes: ByteArray, sync: Boolean = true) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    FileOutputStream(tmp).use { out ->
        out.write(bytes)
        if (sync) out.fd.sync()
    }
    if (tmp.renameTo(file)) return
    val old = File(file.parentFile, file.name + ".old")
    old.delete()
    if (!file.exists() || file.renameTo(old)) {
        if (tmp.renameTo(file)) { old.delete(); return }
        if (old.exists()) old.renameTo(file)
    }
    tmp.delete()
    throw NotReplacedException(file.name)
}

/** The new content was written but could not be moved over the old file, which is kept as it was. */
class NotReplacedException(name: String) : IOException("could not replace $name")
