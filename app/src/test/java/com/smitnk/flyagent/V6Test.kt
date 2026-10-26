package com.smitnk.flyagent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.Calendar

class GitHubTest {
    private val calls = mutableListOf<Triple<String, String, String?>>()
    private val gh = GitHub({ "tok" }) { method, url, headers, body ->
        calls += Triple(method, url.removePrefix("https://api.github.com"), body)
        assertEquals("Bearer tok", headers["Authorization"])
        val path = url.removePrefix("https://api.github.com")
        when {
            path == "/user" -> 200 to """{"login":"smitnk"}"""
            path.startsWith("/repos/smitnk/app/contents/src/Big.kt") && method == "GET" ->
                200 to JSONObject().put("path", "src/Big.kt").put("content", Base64.getEncoder().encodeToString("x".repeat(50_000).toByteArray())).toString()
            path == "/repos/smitnk/app" -> 200 to """{"default_branch":"main"}"""
            path == "/repos/smitnk/app/git/ref/heads/main" -> 200 to """{"object":{"sha":"abc"}}"""
            path == "/repos/smitnk/app/git/refs" -> 201 to "{}"
            path.startsWith("/repos/smitnk/app/contents/src/A.kt?ref=") -> 404 to """{"message":"Not Found"}"""
            path == "/repos/smitnk/app/contents/src/A.kt" && method == "PUT" -> 201 to "{}"
            path == "/repos/smitnk/app/pulls" -> 201 to """{"number":7,"html_url":"https://github.com/smitnk/app/pull/7"}"""
            else -> 404 to """{"message":"Not Found"}"""
        }
    }

    @Test fun shortRepoNameUsesOwnAccountAndReadsAreBounded() {
        val out = gh.run("gh_read", JSONObject().put("repo", "app").put("path", "src/Big.kt"))
        assertTrue(out.contains("truncated at ${GitHub.MAX_READ}")); assertTrue(out.length < GitHub.MAX_READ + 300)
    }

    @Test fun pullRequestCreatesBranchCommitsAndOpensPr() {
        val out = gh.run("gh_pr", JSONObject().put("repo", "smitnk/app").put("title", "Fix crash").put("branch", "flyagent/fix")
            .put("files", JSONArray().put(JSONObject().put("path", "src/A.kt").put("content", "fixed"))))
        assertEquals("Opened pull request #7: https://github.com/smitnk/app/pull/7", out)
        val put = calls.first { it.first == "PUT" }
        assertEquals("flyagent/fix", JSONObject(put.third!!).getString("branch"))
        assertFalse(JSONObject(put.third!!).has("sha")) // new file
        assertEquals("refs/heads/flyagent/fix", JSONObject(calls.first { it.second.endsWith("/git/refs") }.third!!).getString("ref"))
    }

    @Test fun rejectsBadInput() {
        assertThrows(IllegalArgumentException::class.java) { gh.full("../etc") }
        assertThrows(IllegalStateException::class.java) { GitHub({ "" }) { _, _, _, _ -> 200 to "[]" }.run("gh_repos", JSONObject()) }
    }
}

class ScheduleTest {
    @Test fun nextRunIsLaterTodayOrTomorrow() {
        val c = Calendar.getInstance().apply { set(2026, 9, 26, 7, 30, 0); set(Calendar.MILLISECOND, 0) }
        assertEquals(30 * 60_000L, Schedules.delayMs(8, 0, c.timeInMillis))
        assertEquals((24 * 60 - 30) * 60_000L, Schedules.delayMs(7, 0, c.timeInMillis))
    }
}

class NewToolsApprovalTest {
    @Test fun emailAndPrNeedApprovalAndDotsExposeTools() {
        val asked = mutableListOf<String>(); val ran = mutableListOf<String>()
        val llm = FakeLlm(replies = mutableListOf("""{"tool":"draft_email","args":{"to":"a@b.c","subject":"Hi","body":"Hello"}}""", """{"final":"drafted"}"""))
        val mail = Dot.PRESETS.first { it.id == "assistant" }
        Agent(FakeStore(), { n, _ -> ran += n; "opened" }, { listOf(llm) }, null, { null }, { emptyList() }) { t, _ -> asked += t; true }
            .run("email a@b.c", null, Mode.CLOUD, ConventionalController, {}, emptyList(), mail)
        assertEquals(listOf("Send email to a@b.c?"), asked); assertEquals(listOf("draft_email"), ran)
        val coder = Dot.PRESETS.first { it.id == "coder" }
        assertTrue(coder.allows("gh_pr")); assertEquals(16, coder.maxSteps)
        assertTrue(Prompts.system(coder, emptyList()).contains("gh_search")); assertFalse(Prompts.system(mail, emptyList()).contains("gh_pr"))
        assertTrue(Safety.checkTool("gh_read", JSONObject()).ok)
    }

    @Test fun customDotRoundTripsMaxSteps() {
        val d = Dot("x", "X", "", setOf("Web"), 12)
        assertEquals(d, Dot.from(d.json()))
    }
}

class DeepThinkAndBuildTest {
    @Test fun deepThinkReinjectsQuestionEveryLoopAndStopsAtFixedPoint() {
        val llm = FakeLlm(replies = mutableListOf("""{"final":"draft 41"}""", "answer 42", "answer 42", "never used"))
        val r = Agent(FakeStore(), FakeTools()::run, { listOf(llm) }).run("what is 6*7 exactly?", null, Mode.CLOUD, ConventionalController, {}, emptyList(), Dot.DEFAULT, thinkRounds = 3)
        assertEquals("answer 42", r.answer)
        assertEquals(3, llm.calls)                                   // answer + loop1 (changed) + loop2 (same → converged)
        assertTrue(llm.lastMsgs.single().second.startsWith("ORIGINAL QUESTION (re-read it carefully):\nwhat is 6*7 exactly?"))
        assertTrue(llm.lastMsgs.single().second.contains("CURRENT ANSWER (loop 2):\nanswer 42"))
    }

    @Test fun deepThinkOffMakesOneCall() {
        val llm = FakeLlm()
        Agent(FakeStore(), FakeTools()::run, { listOf(llm) }).run("hi there friend", null, Mode.CLOUD, ConventionalController, {})
        assertEquals(1, llm.calls)
    }

    @Test fun cloudBuildWaitAndInstallFromArtifact() {
        val apkDir = java.nio.file.Files.createTempDirectory("apks").toFile()
        val zip = java.io.ByteArrayOutputStream().also { b -> java.util.zip.ZipOutputStream(b).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("app-debug.apk")); z.write(ByteArray(1234) { 7 }); z.closeEntry() } }.toByteArray()
        var installed: java.io.File? = null; var polls = 0
        val gh = GitHub({ "tok" }, { method, url, _, _ ->
            val p = url.removePrefix("https://api.github.com")
            when {
                p == "/repos/me/app/actions/workflows" -> 200 to """{"workflows":[{"id":5,"name":"build","path":".github/workflows/build.yml","state":"active"}]}"""
                p == "/repos/me/app" -> 200 to """{"default_branch":"main"}"""
                p == "/repos/me/app/actions/workflows/5/dispatches" && method == "POST" -> 204 to ""
                p == "/repos/me/app/actions/runs/99" -> { polls++; 200 to (if (polls < 2) """{"status":"in_progress"}""" else """{"status":"completed","conclusion":"success"}""") }
                p == "/repos/me/app/actions/runs/99/artifacts" -> 200 to """{"artifacts":[{"id":3,"name":"FlyAgent-debug-apk","size_in_bytes":1234,"expired":false}]}"""
                else -> 404 to """{"message":"Not Found"}"""
            }
        }, apkDir, { f -> installed = f; "installer opened" }, { url, h -> assertEquals("Bearer tok", h["Authorization"]); assertTrue(url.endsWith("/artifacts/3/zip")); zip }, sleep = {})
        assertTrue(gh.run("gh_build", JSONObject().put("repo", "me/app")).startsWith("Started workflow 'build' on main"))
        assertTrue(gh.run("gh_wait", JSONObject().put("repo", "me/app").put("run_id", 99)).startsWith("Build 99 succeeded"))
        assertTrue(gh.run("gh_install", JSONObject().put("repo", "me/app").put("run_id", 99)).endsWith("installer opened"))
        assertEquals(1234L, installed!!.length())
    }
}
