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
             private val http: (method: String, url: String, headers: Map<String, String>, body: String?) -> Pair<Int, String> = ::httpCall,
             /** Where downloaded APKs go, and how to hand one to Android's installer. */
             private val apkDir: java.io.File? = null,
             private val install: (java.io.File) -> String = { "ERROR: installing isn't available here" },
             private val download: (url: String, headers: Map<String, String>) -> ByteArray = ::downloadBytes,
             private val sleep: (Long) -> Unit = Thread::sleep) {
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
        require(Regex("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)?").matches(repo) && repo.split('/').none { it == "." || it == ".." }) { "bad repo name" }
        if ('/' in repo) return repo
        val me = login ?: JSONObject(api("GET", "/user")).getString("login").also { login = it }
        return "$me/$repo"
    }
    private fun enc(p: String) = p.trim('/').split('/').also { parts -> require(parts.none { it == ".." }) { "bad path" } }
        .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

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
        "gh_runs" -> runs(full(a.getString("repo")))
        "gh_build" -> build(full(a.getString("repo")), a)
        "gh_wait" -> waitRun(full(a.getString("repo")), a.getLong("run_id"))
        "gh_install" -> installApk(full(a.getString("repo")), a.optLong("run_id", 0))
        else -> "ERROR: unknown GitHub tool $tool"
    }

    // ---------- cloud builds: GitHub Actions does the compiling, the phone installs the result ----------

    private fun runs(repo: String) = JSONObject(api("GET", "/repos/$repo/actions/runs?per_page=6")).getJSONArray("workflow_runs").let { arr ->
        (0 until arr.length()).joinToString("\n") { i -> arr.getJSONObject(i).let {
            "run ${it.getLong("id")} · ${it.optString("name")} · ${it.optString("head_branch")} · ${it.optString("status")}/${it.optString("conclusion", "-")} · ${it.optString("html_url")}" } }
            .ifEmpty { "(no workflow runs yet — the repo needs a GitHub Actions workflow)" }
    }

    /** Starts a build: dispatches a workflow (needs `workflow_dispatch:` in its triggers) or re-runs the latest run. */
    private fun build(repo: String, a: JSONObject): String {
        val workflows = JSONObject(api("GET", "/repos/$repo/actions/workflows")).getJSONArray("workflows")
        val all = (0 until workflows.length()).map { workflows.getJSONObject(it) }.filter { it.optString("state") == "active" }
        val wf = a.optString("workflow").ifBlank { null }?.let { w -> all.firstOrNull { it.optString("path").endsWith(w) || it.optString("name") == w } }
            ?: all.singleOrNull() ?: all.firstOrNull { it.optString("path").contains("build", true) } ?: error("no active workflow found in $repo")
        val ref = a.optString("ref").ifBlank { JSONObject(api("GET", "/repos/$repo")).getString("default_branch") }
        return runCatching {
            api("POST", "/repos/$repo/actions/workflows/${wf.getLong("id")}/dispatches", JSONObject().put("ref", ref))
            "Started workflow '${wf.optString("name")}' on $ref. Use gh_runs to get the run id, then gh_wait."
        }.getOrElse {
            // workflows without workflow_dispatch can still be re-run
            val latest = JSONObject(api("GET", "/repos/$repo/actions/workflows/${wf.getLong("id")}/runs?per_page=1")).getJSONArray("workflow_runs")
            if (latest.length() == 0) throw it
            val id = latest.getJSONObject(0).getLong("id"); api("POST", "/repos/$repo/actions/runs/$id/rerun")
            "Re-running workflow '${wf.optString("name")}' (run $id). Use gh_wait with run_id $id."
        }
    }

    /** Polls a run until it finishes (up to ~15 minutes); returns the result and failing step names. */
    private fun waitRun(repo: String, id: Long): String {
        repeat(90) {
            val r = JSONObject(api("GET", "/repos/$repo/actions/runs/$id"))
            if (r.optString("status") == "completed") {
                val c = r.optString("conclusion")
                if (c == "success") return "Build $id succeeded. Use gh_install to install the APK."
                val jobs = JSONObject(api("GET", "/repos/$repo/actions/runs/$id/jobs")).getJSONArray("jobs")
                val failed = (0 until jobs.length()).flatMap { j -> jobs.getJSONObject(j).optJSONArray("steps")?.let { s -> (0 until s.length()).map { s.getJSONObject(it) } } ?: emptyList() }
                    .filter { it.optString("conclusion") == "failure" }.joinToString { it.optString("name") }
                return "Build $id finished: $c. Failed steps: ${failed.ifEmpty { "unknown" }}. ${r.optString("html_url")}"
            }
            sleep(10_000)
        }
        return "Build $id is still running after 15 minutes: check again with gh_wait."
    }

    /** Downloads the APK artifact of a successful run (latest one if run_id is 0) and opens Android's installer. */
    private fun installApk(repo: String, runId: Long): String {
        val dir = apkDir ?: error("installing isn't available here")
        val id = if (runId > 0) runId else JSONObject(api("GET", "/repos/$repo/actions/runs?status=success&per_page=1")).getJSONArray("workflow_runs")
            .let { if (it.length() == 0) error("no successful build yet") else it.getJSONObject(0).getLong("id") }
        val arts = JSONObject(api("GET", "/repos/$repo/actions/runs/$id/artifacts")).getJSONArray("artifacts")
        val art = (0 until arts.length()).map { arts.getJSONObject(it) }.let { l -> l.firstOrNull { it.optString("name").contains("apk", true) } ?: l.firstOrNull() }
            ?: error("run $id has no artifacts — the workflow must upload the APK with actions/upload-artifact")
        if (art.optBoolean("expired")) error("artifact expired; start a new build")
        if (art.optLong("size_in_bytes") > MAX_APK) error("artifact is too large (${art.optLong("size_in_bytes") / 1_000_000} MB)")
        val zip = download("https://api.github.com/repos/$repo/actions/artifacts/${art.getLong("id")}/zip",
            mapOf("Authorization" to "Bearer ${token()}", "Accept" to "application/vnd.github+json"))
        val apk = java.util.zip.ZipInputStream(zip.inputStream()).use { z ->
            generateSequence { z.nextEntry }.firstOrNull { it.name.endsWith(".apk") && !it.name.contains("..") }?.let { e ->
                java.io.File(dir.apply { mkdirs() }, e.name.substringAfterLast('/')).also { f -> f.outputStream().use { z.copyTo(it) } }
            }
        } ?: error("no .apk inside artifact '${art.optString("name")}'")
        return install(apk).let { "Downloaded ${apk.name} (${apk.length() / 1_000_000} MB) from run $id. $it" }
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
        const val MAX_APK = 300_000_000L
        val TOOLS = setOf("gh_repos", "gh_tree", "gh_read", "gh_search", "gh_issue", "gh_pr", "gh_runs", "gh_build", "gh_wait", "gh_install")
        fun downloadBytes(url: String, headers: Map<String, String>): ByteArray {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 120000; c.instanceFollowRedirects = false
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            // GitHub answers with a redirect to a pre-signed storage URL; follow it without sending the token there
            if (c.responseCode in 300..399) return URL(c.getHeaderField("Location")).openStream().use { it.readBytes() }
            if (c.responseCode !in 200..299) error("download failed: HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        }
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
