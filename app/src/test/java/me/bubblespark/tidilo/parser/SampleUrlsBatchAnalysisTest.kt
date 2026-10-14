package me.bubblespark.tidilo.parser

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

class SampleUrlsBatchAnalysisTest {

    @Test
    fun analyzeSampleUrls() = runBlocking {
        val urlsFile = listOf(
            File("../local/urls.txt"),
            File("local/urls.txt"),
            File("../local_share/urls.txt"),
            File("local_share/urls.txt")
        ).firstOrNull { it.exists() }

        if (urlsFile == null) {
            println("No local sample URLs file found. Skipping batch analysis test.")
            return@runBlocking
        }

        val urls = urlsFile.readLines().map { it.trim() }.filter { it.isNotBlank() }

        println("Found ${urls.size} sample URLs to analyze.")

        val reportFile = File("../app/build/reports/web_parser_analysis.md")
        reportFile.parentFile?.mkdirs()

        val sb = StringBuilder()
        sb.append("# Web Parser Analysis Report\n\n")
        sb.append("Analysis of ${urls.size} real-world URLs from `local_share/urls.txt`.\n\n")

        val results = mutableListOf<ParsedWebPage>()
        val failures = mutableListOf<Pair<String, String>>()

        for ((index, url) in urls.withIndex()) {
            println("[$index/${urls.size}] Fetching: $url")
            val result = WebPageParser.parseUrl(url)
            result.fold(
                onSuccess = { page ->
                    results.add(page)
                    println("  -> SUCCESS: HTTP ${page.statusCode}, title='${page.title?.take(30)}...', OGP=${page.ogpTags.size}, JSON-LD=${page.jsonLdList.size}, Readability=${page.readabilityArticle?.charCount ?: 0}c, Jsoup=${page.jsoupBodyCharCount}c")
                },
                onFailure = { err ->
                    failures.add(url to (err.message ?: "Unknown error"))
                    println("  -> FAILED: ${err.message}")
                }
            )
        }

        sb.append("## Overall Statistics\n\n")
        sb.append("- Total URLs: ${urls.size}\n")
        sb.append("- Successful: ${results.size}\n")
        sb.append("- Failed: ${failures.size}\n\n")

        sb.append("## Per-URL Detailed Summary\n\n")
        for ((idx, page) in results.withIndex()) {
            sb.append("### ${idx + 1}. ${page.title ?: page.requestedUrl}\n\n")
            sb.append("- **URL**: `${page.requestedUrl}`\n")
            if (page.finalUrl != page.requestedUrl) {
                sb.append("- **Final (Redirected) URL**: `${page.finalUrl}`\n")
            }
            sb.append("- **HTTP Status**: ${page.statusCode} ${page.statusMessage} (Fetch: ${page.fetchLatencyMs}ms, Parse: ${page.parseLatencyMs}ms, Size: ${page.rawHtmlSizeBytes / 1024} KB)\n")
            sb.append("- **Document**: `title`=\"${page.title.orEmpty()}\", `lang`=\"${page.htmlLang.orEmpty()}\", `canonical`=\"${page.canonicalUrl.orEmpty()}\"\n")
            sb.append("- **OGP Tags (${page.ogpTags.size})**:\n")
            page.ogpTags.forEach { (k, v) ->
                sb.append("  - `$k`: `${v.take(100)}${if (v.length > 100) "..." else ""}`\n")
            }
            sb.append("- **Twitter Tags (${page.twitterTags.size})**:\n")
            page.twitterTags.forEach { (k, v) ->
                sb.append("  - `$k`: `${v.take(100)}${if (v.length > 100) "..." else ""}`\n")
            }
            sb.append("- **Standard Meta (${page.standardMetaTags.size})**:\n")
            page.standardMetaTags.forEach { (k, v) ->
                sb.append("  - `$k`: `${v.take(100)}${if (v.length > 100) "..." else ""}`\n")
            }
            sb.append("- **JSON-LD (${page.jsonLdList.size})**:\n")
            if (page.jsonLdList.isEmpty()) {
                sb.append("  - (None)\n")
            } else {
                page.jsonLdList.forEach { j ->
                    sb.append("  - `@type`: **${j.type ?: "N/A"}** | Name: \"${j.headlineOrName ?: "N/A"}\" | Desc: \"${j.description?.take(80) ?: "N/A"}\"\n")
                }
            }
            sb.append("- **Headings**:\n")
            sb.append("  - H1 (${page.h1List.size}): ${page.h1List.take(3).joinToString("; ")}\n")
            sb.append("  - H2 (${page.h2List.size}): ${page.h2List.take(3).joinToString("; ")}\n")
            sb.append("- **Body Text**:\n")
            sb.append("  - **Readability4J**: ${page.readabilityArticle?.charCount ?: 0} chars | Excerpt: \"${page.readabilityArticle?.excerpt?.take(80) ?: "N/A"}\"\n")
            sb.append("  - **Jsoup Clean**: ${page.jsoupBodyCharCount} chars\n")
            sb.append("  - Preview: \"${(page.readabilityArticle?.textContent ?: page.jsoupBodyText).take(150).replace("\n", " ")}...\"\n\n")
        }

        if (failures.isNotEmpty()) {
            sb.append("## Failures\n\n")
            for ((url, err) in failures) {
                sb.append("- `$url`: $err\n")
            }
            sb.append("\n")
        }

        reportFile.writeText(sb.toString())
        println("Report written to ${reportFile.absolutePath}")
    }
}
