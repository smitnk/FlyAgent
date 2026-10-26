package com.smitnk.flyagent

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/**
 * GitHub REST tools for the Coder Dot: browse, read, search, open issues and pull requests.
 * Reads are bounded (Mobile Harness-style) so a huge file can't flood the model's context.
 */
class GitHub(private val token: () -> String,
             private val http: (method: String, url: String, headers: Map<String, String>, body: String?) -> Pair<Int, String> = ::httpCall) {
    private var login: String? = null

    private fun api(method: String, path: String, body: JSONObject? = null): String {
        val t = token().ifBlank { error("no GitHub token — add one in Settings → GitHub") }
        val (code, text) = http(method, "https://api.github.com$path",
            mapOf("Authorization" to "Bearer $t", "Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28"), body?.toString())
        if (code !in 200..299) error("GitHub $code: ${runCatching { JSONObject(text).optString("message") }.getOrDefault(text.take(200))}")
        return text
    }

    /** "repo" may be "owner/name" or just "name" (then the token's own account). */
    fun full(repo: String): String {
        require(Regex("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)?").matches(repo)) { "bad repo name" }
        if ('/' in repo) return repo
        val me = login ?: JSONObject(api("GET", "/user")).getString("login").also { login = it }
        return "$me/$repo"
    }
    private fun enc(p: String) = p.trim('/').split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    fun run(tool: String, a: JSONObject): String = when (tool) {
        "gh_repos" -> JSONArray(api("GET", "/user/repos?sort=updated&per_page=30")).let { arr ->
            (0 until arr.length()).joinToString("\n") { i -> arr.getJSONObject(i).let { "${it.getString("full_name")}${if (it.optBoolean("private")) " (private)" else ""} — ${it.optString("description").take(80)}" } } }
        "gh_tree" -> {
            val r = api("GET", "/repos/${full(a.getString("repo"))}/contents/${enc(a.optString("path"))}")
            val arr = if (r.trimStart().startsWith("[")) JSONArray(r) else JSONArray().put(JSONObject(r))
            (0 until minOf(arr.length(), 300)).joinToString("\n") { i -> arr.getJSONObject(i).let { "${if (it.getString("type") == "dir") "📁" else "📄"} ${it.getString("path")}" } }
        }
        "gh_read" -> {
            val o = JSONObject(api("GET", "/repos/${full(a.getString("repo"))}/contents/${enc(a.getString("path"))}"))
            val bytes = Base64.getMimeDecoder().decode(o.optString("content"))
            val text = String(bytes, Charsets.UTF_8)
            "File ${o.getString("path")} (${bytes.size} bytes):\n" + text.take(MAX_READ) + if (text.length > MAX_READ) "\n…(truncated at $MAX_READ chars; read a smaller file or search instead)" else ""
        }
        "gh_search" -> JSONObject(api("GET", "/search/code?per_page=20&q=" + URLEncoder.encode("${a.getString("query")} repo:${full(a.getString("repo"))}", "UTF-8")))
            .getJSONArray("items").let { arr -> (0 until arr.length()).joinToString("\n") { arr.getJSONObject(it).getString("path") }.ifEmpty { "(no matches)" } }
        "gh_issue" -> JSONObject(api("POST", "/repos/${full(a.getString("repo"))}/issues", JSONObject().put("title", a.getString("title")).put("body", a.optString("body"))))
            .let { "Opened issue #${it.getInt("number")}: ${it.getString("html_url")}" }
        "gh_pr" -> pullRequest(full(a.getString("repo")), a)
        else -> "ERROR: unknown GitHub tool $tool"
    }

    /** Creates a branch, commits the given full-file contents to it, and opens a PR against the default branch. */
    private fun pullRequest(repo: String, a: JSONObject): String {
        val files = a.getJSONArray("files")
        require(files.length() in 1..20) { "a PR needs 1–20 files" }
        val base = JSONObject(api("GET", "/repos/$repo")).getString("default_branch")
        val branch = a.optString("branch").ifBlank { "flyagent/fix-${System.currentTimeMillis() / 1000}" }
        require(Regex("[A-Za-z0-9._/-]{1,100}").matches(branch) && branch != base) { "bad branch name" }
        val sha = JSONObject(api("GET", "/repos/$repo/git/ref/heads/${enc(base)}")).getJSONObject("object").getString("sha")
        api("POST", "/repos/$repo/git/refs", JSONObject().put("ref", "refs/heads/$branch").put("sha", sha))
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i); val path = f.getString("path")
            val existing = runCatching { JSONObject(api("GET", "/repos/$repo/contents/${enc(path)}?ref=${enc(branch)}")).getString("sha") }.getOrNull()
            api("PUT", "/repos/$repo/contents/${enc(path)}", JSONObject().put("message", "FlyAgent: update $path").put("branch", branch)
                .put("content", Base64.getEncoder().encodeToString(f.getString("content").toByteArray())).apply { existing?.let { put("sha", it) } })
        }
        val pr = JSONObject(api("POST", "/repos/$repo/pulls", JSONObject().put("title", a.getString("title")).put("head", branch).put("base", base)
            .put("body", a.optString("body") + "\n\n_Opened by FlyAgent after approval on the owner's phone._")))
        return "Opened pull request #${pr.getInt("number")}: ${pr.getString("html_url")}"
    }

    companion object {
        const val MAX_READ = 40_000
        val TOOLS = setOf("gh_repos", "gh_tree", "gh_read", "gh_search", "gh_issue", "gh_pr")
        fun httpCall(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = method; c.connectTimeout = 15000; c.readTimeout = 60000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            return code to ((if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty())
        }
    }
}
