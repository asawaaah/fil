package com.asawaaah.fil.data.extract

import com.asawaaah.fil.TestFiles
import com.asawaaah.fil.ai.ModelCatalog
import com.asawaaah.fil.ai.Summarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractionTest {
    @Test
    fun extractorKeepsArticleAndDropsChrome() {
        val result = ArticleExtractor.extract(TestFiles.text("pages/article.html"), "https://times.example.com/2025/rivers")
        assertNotNull(result)
        val html = result!!.html
        assertTrue(html.contains("highest level in decades"))
        assertTrue(html.contains("What experts say"))
        assertTrue(html.contains("reinforce dikes"))
        assertFalse("comments must be removed", html.contains("First! This is a comment"))
        assertFalse("navigation must be removed", html.contains(">World<"))
        assertFalse("newsletter must be removed", html.contains("Subscribe to our newsletter"))
        assertFalse("share buttons must be removed", html.contains("Share on Facebook"))
        // Lazy image fixed and made absolute; relative link made absolute.
        assertTrue(html.contains("https://times.example.com/img/river.jpg"))
        assertTrue(html.contains("https://times.example.com/climate"))
        assertEquals("https://times.example.com/img/hero.jpg", result.leadImage)
        assertEquals("Big news about rivers", result.title)
    }

    @Test
    fun splitArticleBodiesAreMerged() {
        val part = "<p>" + "Une phrase assez longue pour compter comme du vrai texte d'article. ".repeat(6) + "</p>"
        val html = """<html><body><article>
            <div class="post-content">$part<p>Fin de la partie une.</p></div>
            <div class="ad">Publicité</div>
            <div class="post-content">$part<p>Fin de la partie deux.</p></div>
            </article></body></html>"""
        val result = ArticleExtractor.extract(html, "https://x.example/a")!!
        assertTrue(result.html.contains("partie une"))
        assertTrue(result.html.contains("partie deux"))
        assertFalse(result.html.contains("Publicité"))
    }

    @Test
    fun blocksKeepStructureAndInlineStyles() {
        val html = """
            <h2>Titre</h2>
            <p>Un <strong>mot</strong> et un <a href="/lien">lien</a>.<img src="https://x.example/i.jpg" width="600"></p>
            <ul><li>Un</li><li>Deux<ol><li>Imbriqué</li></ol></li></ul>
            <blockquote><p>Citation</p></blockquote>
            <pre>code  avec   espaces</pre>
            <figure><img src="/f.png"><figcaption>Légende</figcaption></figure>
            <hr>
        """.trimIndent()
        val blocks = HtmlBlocks.parse(html, "https://x.example/article")
        val kinds = blocks.map { it::class.simpleName }
        assertEquals(
            listOf("Heading", "Paragraph", "Image", "ListItem", "ListItem", "ListItem", "Quote", "Code", "Image", "Divider"),
            kinds,
        )
        val p = blocks[1] as Block.Paragraph
        assertEquals("Un mot et un lien.", p.text.text)
        val bold = p.text.spans.first { it.kind == SpanKind.BOLD }
        assertEquals("mot", p.text.text.substring(bold.start, bold.end))
        val link = p.text.spans.first { it.kind == SpanKind.LINK }
        assertEquals("lien", p.text.text.substring(link.start, link.end))
        assertEquals("https://x.example/lien", link.url)
        val nested = blocks[5] as Block.ListItem
        assertEquals(1, nested.depth)
        assertTrue(nested.ordered)
        assertEquals("code  avec   espaces", (blocks[7] as Block.Code).text)
        val figure = blocks[8] as Block.Image
        assertEquals("https://x.example/f.png", figure.url)
        assertEquals("Légende", figure.caption)
    }

    @Test
    fun plainTextForSummaries() {
        val blocks = HtmlBlocks.parse("<p>Premier.</p><ul><li>point</li></ul><p>Fin.</p>")
        assertEquals("Premier.\n\n• point\n\nFin.", HtmlBlocks.plainText(blocks))
    }

    @Test
    fun translatedBlocksKeepStructure() {
        val blocks = HtmlBlocks.parse("<h1>Hello</h1><p>World</p><pre>x = 1</pre>")
        val translated = blocks.map { b -> b.prose?.let { b.withProse("FR:$it") } ?: b }
        assertEquals("FR:Hello", (translated[0] as Block.Heading).text.text)
        assertEquals("FR:World", (translated[1] as Block.Paragraph).text.text)
        assertEquals("x = 1", (translated[2] as Block.Code).text)
    }

    @Test
    fun summaryOutputIsCleaned() {
        val raw = "<think>reasoning</think>\n**Points clés :**\n- Premier point.\n* Deuxième point.\n1. Troisième point."
        assertEquals("Points clés :\n• Premier point.\n• Deuxième point.\n• Troisième point.", Summarizer.clean(raw))
        assertEquals("", Summarizer.clean("<think>still thinking"))
    }

    @Test
    fun promptIsInFrenchAndTruncated() {
        val long = "Paragraph. ".repeat(2000)
        val prompt = Summarizer.buildPrompt("Title", long, ModelCatalog.GEMMA4_E2B)
        assertTrue(prompt.contains("en français"))
        assertTrue(prompt.length < Summarizer.MAX_INPUT_CHARS + 600)
        assertFalse(prompt.contains("/no_think"))
        assertTrue(Summarizer.buildPrompt("T", "x", ModelCatalog.QWEN3_1_7B).contains("/no_think"))
    }
}
