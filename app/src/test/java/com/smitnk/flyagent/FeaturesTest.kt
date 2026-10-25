package com.smitnk.flyagent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AttachmentsTest {
    private fun zip(vararg files: Pair<String, ByteArray>) = ByteArrayOutputStream().also { b ->
        ZipOutputStream(b).use { z -> files.forEach { (n, d) -> z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
    }.toByteArray()

    @Test fun readsZipListingAndTextFiles() {
        val out = Attachments.read("project.zip", zip("src/Main.kt" to "fun main() = println(1)".toByteArray(), "logo.png" to ByteArray(50) { 1 }, "README.md" to "# Hello".toByteArray()))
        assertTrue(out.contains("3 entries")); assertTrue(out.contains("📄 logo.png"))
        assertTrue(out.contains("----- src/Main.kt -----\nfun main() = println(1)")); assertTrue(out.contains("# Hello"))
        assertTrue(out.contains("1 non-text files listed only"))
    }

    @Test fun zipBombIsCapped() {
        val big = ByteArray(5_000_000) { 'a'.code.toByte() } // compresses to a few KB
        val out = Attachments.read("bomb.zip", zip("a.txt" to big, "b.txt" to big, "c.txt" to big, "d.txt" to big))
        assertTrue(out.length < Attachments.MAX_TOTAL + 2000); assertTrue(out.contains("truncated"))
    }

    @Test fun textAndUnsupported() {
        assertTrue(Attachments.read("notes.csv", "a,b\n1,2".toByteArray()).contains("a,b\n1,2"))
        assertThrows(IllegalStateException::class.java) { Attachments.read("song.mp3", ByteArray(10)) }
    }
}

class McpTest {
    @Test fun parsesJsonAndSse() {
        assertEquals(1, McpClient.parse("""{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}""")!!.getInt("id"))
        val sse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[]}}\n\n"
        assertEquals(3, McpClient.parse(sse)!!.getInt("id"))
    }

    @Test fun handshakeListsAndCallsToolsWithSessionAndAuth() {
        val sent = mutableListOf<Pair<Map<String, String>, JSONObject>>()
        val fake = { _: String, h: Map<String, String>, body: String ->
            val req = JSONObject(body); sent += h to req
            val result = when (req.getString("method")) {
                "initialize" -> JSONObject().put("protocolVersion", "2025-06-18")
                "tools/list" -> JSONObject("""{"tools":[{"name":"list_events","description":"Calendar","inputSchema":{"properties":{"day":{}}},"annotations":{"readOnlyHint":true}},{"name":"send_email","description":"Send"}]}""")
                "tools/call" -> JSONObject("""{"content":[{"type":"text","text":"2 events"}]}""")
                else -> null
            }
            mapOf("Mcp-Session-Id" to "s1") to (result?.let { "data: " + JSONObject().put("jsonrpc", "2.0").put("id", req.opt("id")).put("result", it) } ?: "")
        }
        val c = McpClient(Connector("cal", "https://x", "tok"), fake)
        val tools = c.connect()
        assertEquals(listOf("list_events", "send_email"), tools.map { it.name })
        assertTrue(tools[0].readOnly); assertFalse(tools[1].readOnly)
        assertEquals("2 events", c.call("list_events", JSONObject().put("day", "today")))
        assertEquals("Bearer tok", sent.last().first["Authorization"]); assertEquals("s1", sent.last().first["Mcp-Session-Id"])
        assertFalse(sent[1].second.has("id")) // initialized is a notification
    }
}

class DotsAndApprovalTest {
    private val sendEmail = McpTool("mail", "send_email", "Send", JSONObject(), readOnly = false)
    private val listMail = McpTool("mail", "list", "List", JSONObject(), readOnly = true)

    private fun agent(store: FakeStore, llm: FakeLlm, approve: Boolean, asked: MutableList<String>, calls: MutableList<String>) =
        Agent(store, { n, a -> calls += n; if (n == "save_page") "saved" else "ok:$n" }, { listOf(llm) }, null, { null }, { listOf(sendEmail, listMail) }) { t, _ -> asked += t; approve }

    @Test fun savePageNeedsApprovalAndDeclineIsRespected() {
        val asked = mutableListOf<String>(); val calls = mutableListOf<String>()
        val llm = FakeLlm(replies = mutableListOf("""{"tool":"save_page","args":{"title":"Brief","content":"# Hi"}}""", """{"final":"ok"}"""))
        agent(FakeStore(), llm, approve = false, asked, calls).run("save a brief", null, Mode.CLOUD, ConventionalController, {})
        assertEquals(listOf("Save page \"Brief\"?"), asked); assertTrue(calls.isEmpty())
        assertTrue(llm.lastMsgs.last().second.contains("declined"))
    }

    @Test fun readOnlyConnectorToolRunsWithoutApprovalWriteToolAsks() {
        val asked = mutableListOf<String>(); val calls = mutableListOf<String>()
        val llm = FakeLlm(replies = mutableListOf(
            """{"tool":"mcp","args":{"server":"mail","name":"list","arguments":{}}}""",
            """{"tool":"mcp","args":{"server":"mail","name":"send_email","arguments":{"to":"a@b.c"}}}""", """{"final":"done"}"""))
        agent(FakeStore(), llm, approve = true, asked, calls).run("check and reply to mail", null, Mode.CLOUD, ConventionalController, {})
        assertEquals(listOf("Run mail/send_email?"), asked); assertEquals(listOf("mcp", "mcp"), calls)
    }

    @Test fun dotRestrictsToolsAndPrompt() {
        val writer = Dot.PRESETS.first { it.id == "writer" }
        assertFalse(writer.allows("web_search")); assertTrue(writer.allows("save_page"))
        val p = Prompts.system(writer, listOf(sendEmail))
        assertTrue(p.contains("You are Writer")); assertFalse(p.contains("web_search")); assertFalse(p.contains("send_email"))
        val calls = mutableListOf<String>()
        val llm = FakeLlm(replies = mutableListOf("""{"tool":"web_search","args":{"query":"x"}}""", """{"final":"ok"}"""))
        agent(FakeStore(), llm, true, mutableListOf(), calls).run("draft", null, Mode.CLOUD, ConventionalController, {}, emptyList(), writer)
        assertTrue(calls.isEmpty()); assertTrue(llm.lastMsgs.last().second.contains("not allowed"))
    }
}
