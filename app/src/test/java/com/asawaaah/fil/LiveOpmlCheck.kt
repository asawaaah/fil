package com.asawaaah.fil

import com.asawaaah.fil.data.feeds.FeedLoader
import com.asawaaah.fil.data.feeds.Opml
import com.asawaaah.fil.data.net.Http
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Loads every feed of a real OPML file with the app's logic, and writes a report.
 * Skipped unless FIL_OPML points to a file, e.g.:
 *   $env:FIL_OPML = "C:\path\to\file.opml"; .\build.ps1 testDebugUnitTest --tests "*LiveOpmlCheck*"
 * The report goes to FIL_REPORT (default: fil-opml-report.txt in the temp folder).
 */
class LiveOpmlCheck {
    @Test
    fun importRealFile() = runBlocking {
        val path = System.getenv("FIL_OPML")
        assumeTrue(path != null && File(path).exists())
        val doc = Opml.parse(File(path!!).readBytes())
        val loader = FeedLoader(Http())
        val semaphore = Semaphore(12)

        data class Line(val title: String, val url: String, val status: String, val detail: String)

        val lines = doc.feeds.map { f ->
            async {
                semaphore.withPermit {
                    when (val r = loader.load(f.xmlUrl, repair = true, siteUrl = f.htmlUrl)) {
                        is FeedLoader.Result.Ok -> {
                            val status = when {
                                !r.changed(f.xmlUrl) -> "OK"
                                r.url == "https://" + f.xmlUrl.removePrefix("http://") -> "OK_HTTPS"
                                else -> "OK_REPAIRED"
                            }
                            Line(f.title, f.xmlUrl, status, if (status == "OK") "${r.feed.items.size} items" else "-> ${r.url}")
                        }
                        is FeedLoader.Result.Failed -> Line(f.title, f.xmlUrl, "FAILED_${r.kind}", r.reason)
                        FeedLoader.Result.NotModified -> Line(f.title, f.xmlUrl, "OK", "not modified")
                    }
                }
            }
        }.awaitAll()

        val report = buildString {
            appendLine("Total: ${lines.size} (doublons dans le fichier : ${doc.duplicatesInFile})")
            lines.groupingBy { it.status }.eachCount().toSortedMap().forEach { (k, v) -> appendLine("  $k: $v") }
            appendLine()
            appendLine("Echecs par raison :")
            lines.filter { it.status.startsWith("FAILED") }
                .groupingBy { it.status + "  " + it.detail.replace(Regex("« .* »"), "« … »").replace(Regex("\\(.*\\)"), "(…)") }
                .eachCount().entries.sortedByDescending { it.value }
                .forEach { (k, v) -> appendLine("  %3d  %s".format(v, k)) }
            appendLine()
            appendLine("Reparees :")
            lines.filter { it.status == "OK_REPAIRED" }.forEach { appendLine("  ${it.title} | ${it.url} ${it.detail}") }
            appendLine()
            appendLine("Echecs :")
            lines.filter { it.status.startsWith("FAILED") }.sortedBy { it.status + it.detail }
                .forEach { appendLine("  ${it.status} | ${it.title} | ${it.url} | ${it.detail}") }
        }
        val out = File(System.getenv("FIL_REPORT") ?: File(System.getProperty("java.io.tmpdir"), "fil-opml-report.txt").path)
        out.writeText(report)
        println(report.lineSequence().take(40).joinToString("\n"))
    }
}
