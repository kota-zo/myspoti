package app.myspoti.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStyleTest {
    private fun manifest(ui: String?, actions: String = """[{ "name": "ui.style", "why": "A warm orange look" }]""") = Manifest.parse(
        """{ "id": "io.github.you.look", "name": "Look", "version": "1.0", "author": "You", "actions": $actions${ui?.let { ", \"ui\": $it" } ?: ""} }""",
    )

    @Test fun theSampleLookInstalls() {
        val m = manifest(
            """{ "colors": { "accent": "#FF9E3D", "background": "#120B06" }, "hide": ["home.dj"],
                 "order": { "nav": ["library", "home", "search"] }, "styles": { "text": { "scale": 1.1 } },
                 "blocks": { "home": [{ "type": "text", "text": "Hello", "style": "title" }] } }""",
        )
        assertEquals(emptyList<String>(), m.problems())
        assertEquals(emptyList<String>(), m.uiWarnings())
        assertTrue(Action.UI_STYLE in m.actionSet())
        assertEquals(Category.LOOK, Category.of("look", m.actionSet()))
    }

    @Test fun theActionNeedsAUi() {
        assertTrue(manifest(null).problems().any { it.startsWith("ui.style") })
        assertTrue(manifest("{}").problems().any { it.startsWith("ui: nothing") })
    }

    @Test fun badValuesRefuseTheLook() {
        val p = manifest("""{ "colors": { "accent": "orange" }, "order": { "nav": ["home"] }, "styles": { "text": { "scale": 3 } }, "blocks": { "home": "x" } }""").problems()
        assertTrue(p.toString(), p.any { it.startsWith("ui.colors.accent") })
        assertTrue(p.toString(), p.any { it.startsWith("ui.order.nav") })
        assertTrue(p.toString(), p.any { it.startsWith("ui.styles.text.scale") })
        assertTrue(p.toString(), p.any { it.startsWith("ui.blocks.home") })
    }

    @Test fun unknownThingsAreWarningsNotProblems() {
        val m = manifest("""{ "colors": { "accent": "#FFFFFF", "glow": "#FF0000" }, "hide": ["about"], "styles": { "cover": { "corner": 4 } } }""")
        assertEquals(emptyList<String>(), m.problems())
        assertEquals(3, m.uiWarnings().size)
    }

    @Test fun aUiKeyWithoutTheActionChangesNothing() {
        // Before ui.style, "ui" was an unknown key: a manifest that had one still installs as it did.
        val m = manifest("""{ "colors": { "accent": "nonsense" } }""", """[{ "name": "notify", "why": "Says hello" }]""")
        assertEquals(emptyList<String>(), m.problems())
        assertNull(m.look())
        assertEquals(listOf("ui: ignored without the ui.style action"), m.uiWarnings())
    }

    @Test fun theFirstLookWinsAndLightPagesAreLeftOut() {
        val first = UiStyle(colors = mapOf("accent" to "#FF9E3D", "background" to "#FFFFFF"), hide = listOf("home.dj"))
        val second = UiStyle(colors = mapOf("accent" to "#00FF00", "background" to "#101010", "surface" to "#80202020"), hide = listOf("player.capsule", "nope"),
            order = mapOf("nav" to listOf("search", "home", "library")))
        val look = AppliedLook.of(listOf("a" to first, "b" to second))
        assertEquals(0xFFFF9E3D.toInt(), look.colors["accent"])
        // White is too light for the app's white text; the second one's dark page applies.
        assertEquals(0xFF101010.toInt(), look.colors["background"])
        // A see-through surface is not applied.
        assertNull(look.colors["surface"])
        assertEquals(setOf(UiPart.HOME_DJ, UiPart.PLAYER_CAPSULE), look.hidden)
        assertEquals(listOf("search", "home", "library"), look.navOrder)
        assertNull(look.textScale)
    }

    @Test fun noProtectedSurfaceCanBeHidden() {
        val protectedKeys = ProtectedSurface.entries.map { it.key }.toSet()
        assertTrue(UiPart.entries.none { it.key in protectedKeys || it.key.substringBefore('.') in protectedKeys })
    }
}
