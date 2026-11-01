package com.music.spotui.data.offline

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OfflineIndexTest {
    private fun entry(id: String) = JSONObject().put("id", id).put("file", "$id.m4a").put("cover", "$id.jpg")

    private fun folder(index: String?, vararg ids: String): File {
        val root = Files.createTempDirectory("offline").toFile()
        File(root, "audio").mkdirs(); File(root, "covers").mkdirs()
        ids.forEach { File(root, "audio/$it.m4a").writeText("x"); File(root, "covers/$it.jpg").writeText("x") }
        index?.let { File(root, "index.json").writeText(it) }
        return root
    }

    @Test fun theProvidingExtensionIsKeptAndOlderEntriesHaveNone() {
        val old = OfflineTrack.fromJson(entry("a"))
        assertEquals(null, old.providerId)
        val kept = OfflineTrack.fromJson(old.copy(providerId = "io.github.x.dl").toJson())
        assertEquals("io.github.x.dl", kept.providerId)
        assertFalse(old.toJson().has("providerId"))
    }

    @Test fun truncatedIndexKeepsEveryFile() {
        val root = folder("[{\"id\":\"a\",\"file\":\"a.m4a\"", "a", "b")
        OfflineStore.load(root)
        assertEquals(2, kept(root, "audio"))
        assertEquals(2, kept(root, "covers"))
        assertTrue(root.list()!!.any { it.startsWith("index.corrupt-") })
    }

    @Test fun unreadableEntryKeepsEveryFile() {
        val root = folder(JSONArray().put(entry("a")).put(JSONObject().put("title", "no id")).toString(), "a", "b")
        OfflineStore.load(root)
        assertEquals(setOf("a"), OfflineStore.tracks.keys.toSet())
        assertEquals(2, kept(root, "audio"))
    }

    /** Files still on the phone: in place, or kept aside in the recovered folder. */
    private fun kept(root: File, sub: String) =
        File(root, sub).list()!!.size + (File(root, "recovered/$sub").list()?.size ?: 0)

    @Test fun damagedIndexWithAnOlderBackupKeepsTheSongsAddedSince() {
        val root = folder(null)
        OfflineStore.load(root)
        listOf("a", "b").forEach { id ->
            File(root, "audio/$id.m4a").writeText("x"); File(root, "covers/$id.jpg").writeText("x")
            OfflineStore.add(OfflineTrack.fromJson(entry(id)))
        }
        // The index lists a and b, its backup only a; then the index is damaged.
        File(root, "index.json").writeText("{oops")
        OfflineStore.load(root)
        assertEquals(setOf("a"), OfflineStore.tracks.keys.toSet())
        assertTrue(File(root, "audio/a.m4a").exists())
        assertTrue(File(root, "recovered/audio/b.m4a").exists())
        assertTrue(File(root, "recovered/covers/b.jpg").exists())
        // The index rewritten from the backup, read in full, sweeps nothing that was kept aside.
        OfflineStore.load(root)
        assertTrue(File(root, "recovered/audio/b.m4a").exists())
        root.deleteRecursively()
    }

    @Test fun backupIsUsedWhenTheIndexIsDamaged() {
        val root = folder("{oops", "a", "b")
        File(root, "index.json.bak").writeText(JSONArray().put(entry("a")).put(entry("b")).toString())
        OfflineStore.load(root)
        assertEquals(setOf("a", "b"), OfflineStore.tracks.keys.toSet())
        assertEquals(2, File(root, "audio").list()!!.size)
    }

    @Test fun validIndexStillRemovesLeftovers() {
        val root = folder(JSONArray().put(entry("a")).toString(), "a", "b")
        OfflineStore.load(root)
        assertEquals(setOf("a"), OfflineStore.tracks.keys.toSet())
        assertFalse(File(root, "audio/b.m4a").exists())
        assertTrue(File(root, "audio/a.m4a").exists())
    }
}
