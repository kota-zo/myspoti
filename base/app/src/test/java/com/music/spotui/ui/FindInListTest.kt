package com.music.spotui.ui

import com.music.spotui.ui.components.matchesFind
import com.music.spotui.ui.components.withRowKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FindInListTest {
    @Test fun blankMatchesEverything() = assertTrue(matchesFind("  ", "Halo", "Beyoncé"))
    @Test fun ignoresCaseAndAccents() = assertTrue(matchesFind("BEYONCE", "Halo", "Beyoncé"))
    @Test fun everyWordInAnyField() = assertTrue(matchesFind("beyonce hal", "Halo", "Beyoncé", "I Am... Sasha Fierce"))
    @Test fun missingWordFails() = assertFalse(matchesFind("beyonce crazy", "Halo", "Beyoncé"))
    @Test fun matchesPartOfAWord() = assertTrue(matchesFind("sava", "J'SAVAIS", "Ikaz Boi"))

    @Test fun rowKeysAreUniqueAndTravelWithTheirRows() {
        val (rows, keys) = withRowKeys(listOf("a", "b", "a", "a")) { it }
        assertEquals(listOf("a", "b", "a", "a"), rows)
        assertEquals(listOf("a", "b", "a#2", "a#3"), keys)
    }
}
