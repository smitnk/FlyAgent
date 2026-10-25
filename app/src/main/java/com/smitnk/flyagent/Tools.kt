package com.smitnk.flyagent

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

/** Tool executor + safety controller: file access is confined to the app workspace. */
class Tools(val workspace: File, private val memory: Store, private val brain: () -> BrainEngine? = { null }) {
    init { workspace.mkdirs() }

    fun run(name: String, args: JSONObject): String = try {
        val v = Safety.checkTool(name, args)
        if (!v.ok) "ERROR: blocked by safety: ${v.reason}" else when (name) {
            "web_search" -> webSearch(args.getString("query"))
            "fetch_page" -> fetchPage(args.getString("url"))
            "write_file" -> writeFile(args.getString("name"), args.getString("content"))
            "read_file" -> safeFile(args.getString("name")).readText().take(8000)
            "list_files" -> workspace.listFiles()?.joinToString("\n") { "${it.name} (${it.length()} B)" }.orEmpty().ifEmpty { "(empty)" }
            "recall" -> memory.recall(args.getString("query"), 5).joinToString("\n") { "#${it.id} ${it.task} -> ${it.outcome.take(200)}" }.ifEmpty { "(no matching memories)" }
            "brain_sim" -> BrainLab.run(brain(), args)
            else -> "ERROR: unknown tool $name"
        }
    } catch (e: Exception) { "ERROR: ${e.javaClass.simpleName}: ${e.message}" }

    fun webSearch(q: String): String {
        // shortcut: scrapes DuckDuckGo's HTML page (no API key); swap for a search API if it breaks
        val html = get("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8"))
        return SearchParser.parse(html).take(6).joinToString("\n") { "- ${it.first}\n  ${it.second}" }.ifEmpty { "(no results)" }
    }

    fun fetchPage(url: String): String {
        return HtmlText.strip(get(url)).take(5000)
    }

    fun writeFile(name: String, content: String): String {
        val f = safeFile(name)
        f.writeText(content)
        return "Saved ${f.name} (${f.length()} B, sha256 ${sha256(f.readBytes()).take(16)}…) at ${f.absolutePath}"
    }

    private fun safeFile(name: String): File {
        require(SafeName.ok(name)) { "file name must be letters, digits, . _ - only" }
        return File(workspace, name)
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) FlyAgent/0.1")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    companion object {
        fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}

object HtmlText {
    fun strip(html: String) = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("\\s+"), " ").trim()
}

object SearchParser {
    private val link = Regex("""class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    /** Returns (title, real url) pairs, unwrapping DuckDuckGo's /l/?uddg= redirect links. */
    fun parse(html: String): List<Pair<String, String>> = link.findAll(html).map { m ->
        var href = m.groupValues[1].replace("&amp;", "&")
        Regex("uddg=([^&]+)").find(href)?.let { href = URLDecoder.decode(it.groupValues[1], "UTF-8") }
        if (href.startsWith("//")) href = "https:$href"
        HtmlText.strip(m.groupValues[2]) to href
    }.filter { it.second.startsWith("http") }.toList()
}
