package app.myspoti.spotify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Biographies and audiobook descriptions arrive as small HTML snippets. */
class HtmlTextTest {
    @Test fun `the basic entities`() {
        assertEquals("Tom & Jerry say \"hi\" it's <ok>", decodeHtmlEntities("Tom &amp; Jerry say &quot;hi&quot; it&apos;s &lt;ok&gt;"))
    }

    @Test fun `decimal and hex entities`() {
        assertEquals("it's ’ 🎵 🎵", decodeHtmlEntities("it&#39;s &#8217; &#x1F3B5; &#127925;"))
        assertEquals("A", decodeHtmlEntities("&#x41;"))
    }

    @Test fun `named entities of the Latin-1 block, Greek and punctuation`() {
        assertEquals("café ñ ÿ À ©", decodeHtmlEntities("caf&eacute; &ntilde; &yuml; &Agrave; &copy;"))
        assertEquals("a–b — … “q” € Ωω", decodeHtmlEntities("a&ndash;b &mdash; &hellip; &ldquo;q&rdquo; &euro; &Omega;&omega;"))
        assertEquals(" ", decodeHtmlEntities("&nbsp;"))
    }

    @Test fun `decoded once, unknown or invalid ones left as written`() {
        assertEquals("&lt; and &#38;", decodeHtmlEntities("&amp;lt; and &amp;#38;"))
        assertEquals("&bogus; &#0; &#xD800; &#1114112; AT&T; &", decodeHtmlEntities("&bogus; &#0; &#xD800; &#1114112; AT&T; &"))
        assertEquals("no entity", decodeHtmlEntities("no entity"))
    }

    @Test fun `the table lines up with Latin-1`() {
        assertEquals(" ¡¢", decodeHtmlEntities("&nbsp;&iexcl;&cent;"))
        assertEquals("ÿ", decodeHtmlEntities("&yuml;"))
        assertEquals("÷", decodeHtmlEntities("&divide;"))
        assertEquals("ςσ", decodeHtmlEntities("&sigmaf;&sigma;"))
        assertEquals("Σ", decodeHtmlEntities("&Sigma;"))
    }

    @Test fun `tags go, line breaks stay`() {
        assertEquals("Born in Paris & raised in <Lyon>.\n\nSecond line", stripHtml("<p>Born in <a href=\"spotify:artist:x\">Paris</a> &amp; raised in &lt;Lyon&gt;.</p><br/>\n<br>Second line"))
        assertEquals("Tyler, The Creator", stripHtml("  <b>Tyler, The Creator</b>  "))
    }
}
