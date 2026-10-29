package com.music.spotui.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Replaces [file] with [text] whole or not at all: written to a temporary file next to it, flushed
 * to the disk, then moved over it. A move that fails (the target in the way on some file systems)
 * is tried once more after deleting the target; failing again, the old file is left as it was and
 * an [IOException] says so, instead of the app carrying on with a state it never saved.
 */
fun writeTextSafely(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    FileOutputStream(tmp).use { out ->
        out.write(text.toByteArray())
        out.fd.sync()
    }
    if (tmp.renameTo(file)) return
    if (file.delete() && tmp.renameTo(file)) return
    tmp.delete()
    throw IOException("could not replace ${file.name}")
}
